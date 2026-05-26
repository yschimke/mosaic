package com.jakewharton.mosaic.e2e

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A specific terminal emulator that can host the sample binary and be screenshotted. The
 * `start` command is parameterised on display geometry and the path of the sample executable.
 *
 * The two implementations — Kitty and Ghostty — diverge only in their command lines; they share
 * `xdotool` for input simulation and `import` for screenshotting.
 */
internal sealed class TerminalLauncher(val name: String) {

	/** Bash-style probe: returns true iff the launcher's executable is on PATH. */
	fun available(): Boolean = TerminalLauncher.binaryOnPath(executable)

	/** Path-resolvable name of the terminal binary. */
	protected abstract val executable: String

	/**
	 * Build the argv to spawn the terminal hosting [executable on display] for [sampleCommand]
	 * at the given grid dimensions.
	 *
	 * The command MUST keep the terminal alive after the sample exits — the consumer wants to
	 * screenshot the post-exit screen too. Kitty's `--hold` and Ghostty's `--wait-after-command`
	 * achieve that.
	 */
	abstract fun command(display: String, cols: Int, rows: Int, sampleCommand: List<String>): List<String>

	object Kitty : TerminalLauncher("kitty") {
		override val executable = "kitty"
		override fun command(display: String, cols: Int, rows: Int, sampleCommand: List<String>): List<String> {
			// `--class` lets `xdotool search --class` find the right window, even when there
			// are other kitty instances on the same display.
			return listOf(
				"kitty",
				"--class=mosaic-e2e-kitty",
				"--title=mosaic-e2e-kitty",
				"-o", "initial_window_width=${cols}c",
				"-o", "initial_window_height=${rows}c",
				"-o", "font_size=11",
				"-o", "enable_audio_bell=no",
				"-o", "remember_window_size=no",
				"--hold",
				"--",
			) + sampleCommand
		}
	}

	object Ghostty : TerminalLauncher("ghostty") {
		override val executable = "ghostty"
		override fun command(display: String, cols: Int, rows: Int, sampleCommand: List<String>): List<String> {
			// Ghostty 1.0+ uses libghostty + GTK. `--class` becomes WM_CLASS on X11.
			return listOf(
				"ghostty",
				"--class=mosaic-e2e-ghostty",
				"--title=mosaic-e2e-ghostty",
				"--window-width=$cols",
				"--window-height=$rows",
				"--font-size=11",
				"--wait-after-command=true",
				"--command=${sampleCommand.joinToString(" ")}",
			)
		}
	}

	companion object {
		fun all(): List<TerminalLauncher> = listOf(Kitty, Ghostty)

		fun binaryOnPath(name: String): Boolean {
			return try {
				val p = ProcessBuilder("sh", "-c", "command -v $name >/dev/null 2>&1").start()
				if (!p.waitFor(1, TimeUnit.SECONDS)) {
					p.destroyForcibly()
					false
				} else {
					p.exitValue() == 0
				}
			} catch (_: Throwable) {
				false
			}
		}
	}
}

/**
 * One end-to-end run: spawn the terminal, give the sample some warm-up time, drive keystrokes,
 * capture a series of PNG screenshots, then tear everything down.
 */
internal class TerminalSession(
	val launcher: TerminalLauncher,
	val display: String,
	private val screenshotDir: File,
) : AutoCloseable {

	private var process: Process? = null

	fun start(cols: Int, rows: Int, sampleCommand: List<String>) {
		screenshotDir.mkdirs()
		val cmd = launcher.command(display, cols, rows, sampleCommand)
		val env = mapOf(
			"DISPLAY" to display,
			// Force UTF-8 so the sample's `▀` half-blocks survive the pipe. Kitty + Ghostty
			// both expect this anyway, but tests may inherit a stripped-down env.
			"LANG" to "en_US.UTF-8",
			"LC_ALL" to "en_US.UTF-8",
			// Some terminal emulators read XDG_RUNTIME_DIR; default to /tmp so isolated test
			// envs don't crash setting up sockets.
			"XDG_RUNTIME_DIR" to (System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"),
		)
		val pb = ProcessBuilder(cmd).apply {
			environment().putAll(env)
			redirectErrorStream(true)
			redirectOutput(ProcessBuilder.Redirect.appendTo(File("/tmp/${launcher.name}-mosaic-e2e.log")))
		}
		process = pb.start()
		waitForWindow()
	}

	private fun waitForWindow() {
		// The window we want is identifiable by the WM_CLASS we set above. Poll xdotool until
		// it appears — typical hit is <500ms.
		val cls = "mosaic-e2e-${launcher.name}"
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
		while (System.nanoTime() < deadline) {
			val rc = ProcessBuilder("xdotool", "search", "--class", cls)
				.apply { environment()["DISPLAY"] = display }
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start().also { it.waitFor(500, TimeUnit.MILLISECONDS) }
				.exitValue()
			if (rc == 0) {
				// Also need the sample to have drawn its first frame. There's no portable signal
				// for that — the consumer's harness uses a fixed 400ms settle and it has proved
				// robust across CI; we follow.
				Thread.sleep(400)
				return
			}
			Thread.sleep(80)
		}
		error("${launcher.name} window did not appear on $display within 8s")
	}

	fun sendKeys(vararg keys: String) {
		for (k in keys) {
			run("xdotool", "search", "--class", "mosaic-e2e-${launcher.name}", "key", "--delay", "30", k)
		}
		Thread.sleep(200)
	}

	fun type(text: String) {
		run("xdotool", "search", "--class", "mosaic-e2e-${launcher.name}", "type", "--delay", "30", text)
		Thread.sleep(200)
	}

	/** Snap the root window via ImageMagick `import` and write a PNG into [screenshotDir]. */
	fun capture(name: String): File {
		// Brief settle so a recompose triggered by the previous keystroke has time to land.
		Thread.sleep(250)
		val out = File(screenshotDir, "${launcher.name}-$name.png")
		run("import", "-display", display, "-window", "root", out.absolutePath)
		require(out.exists() && out.length() > 1024) {
			"screenshot did not write a non-trivial PNG: $out (${out.length()} bytes)"
		}
		return out
	}

	override fun close() {
		val p = process ?: return
		// Try a graceful Ctrl-C inside the window first so the sample can clean up its TTY
		// state.
		runCatching {
			ProcessBuilder("xdotool", "search", "--class", "mosaic-e2e-${launcher.name}", "key", "ctrl+c")
				.apply { environment()["DISPLAY"] = display }
				.start().waitFor(500, TimeUnit.MILLISECONDS)
		}
		Thread.sleep(150)
		p.destroy()
		if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
		process = null
	}

	private fun run(vararg argv: String) {
		val pb = ProcessBuilder(*argv)
		pb.environment()["DISPLAY"] = display
		pb.redirectErrorStream(true)
		val p = pb.start()
		val ok = p.waitFor(3, TimeUnit.SECONDS)
		check(ok && p.exitValue() == 0) {
			"command failed (rc=${if (ok) p.exitValue() else "timeout"}): ${argv.joinToString(" ")}"
		}
	}
}
