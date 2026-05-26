package com.jakewharton.mosaic.e2e

import java.io.File
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/**
 * End-to-end visual capture of the `:samples:rawimage` Mosaic sample running inside real
 * terminal emulators (kitty and ghostty), hosted on a virtual X server (Xvnc) and screenshotted
 * with ImageMagick `import`. Exists to prove the `RawText` library feature actually keeps
 * layout aligned when an emulator with full truecolor support consumes the SGR payloads.
 *
 * ## Skip semantics
 *
 * Every binary in the harness chain — Xvnc, xdotool, import — plus *at least one* of {kitty,
 * ghostty} must be on PATH. If a runner lacks any prerequisite or both terminals, the test
 * skips green; CI runners that want it to execute install the binaries first
 * (`apt install tigervnc-standalone-server kitty ghostty xdotool imagemagick`). On a runner with
 * exactly one terminal the test runs against just that one — losing coverage but not the run.
 *
 * ## Why two terminals
 *
 * Kitty and Ghostty have independent SGR / VT-protocol implementations. The PR's `LIMITATIONS`
 * note about width tracking is specifically about avoiding payload-induced layout drift in
 * *consumer* terminals, so we want at least two distinct ones in the matrix to catch a
 * regression that would happen on only one.
 */
class RawTextE2ETest {

	@Test fun screenshotsOnEachAvailableTerminal() {
		assumeTrue("Xvnc not on PATH — skipping", TerminalLauncher.binaryOnPath("Xvnc"))
		assumeTrue("xdotool not on PATH — skipping", TerminalLauncher.binaryOnPath("xdotool"))
		assumeTrue("import (imagemagick) not on PATH — skipping", TerminalLauncher.binaryOnPath("import"))

		val terminals = TerminalLauncher.all().filter { it.available() }
		assumeTrue(
			"neither kitty nor ghostty on PATH — skipping",
			terminals.isNotEmpty(),
		)

		val sampleDirProp = System.getProperty("mosaic.e2e.sampleDir")
			?: error("system property 'mosaic.e2e.sampleDir' missing — wire via Gradle")
		val sampleDir = File(sampleDirProp)
		val sampleBin = File(sampleDir, "bin/rawimage")
		assumeTrue(
			"sample binary not built at $sampleBin — run :samples:rawimage:installJvmDist",
			sampleBin.canExecute(),
		)

		val screenshotRoot = File(
			System.getProperty("mosaic.e2e.screenshotDir") ?: "build/e2e-screenshots",
		).absoluteFile

		val capturedFiles = mutableListOf<File>()

		XvncDisplay().use { display ->
			display.start(width = 1280, height = 800)
			for (terminal in terminals) {
				val dir = File(screenshotRoot, terminal.name)
				TerminalSession(terminal, display.display, dir).use { session ->
					session.start(
						cols = 100,
						rows = 30,
						sampleCommand = listOf(sampleBin.absolutePath),
					)
					// Initial frame: the sample paints its first RawText row. We want a clean
					// snapshot before any animation has muddied the picture.
					capturedFiles += session.capture("01-initial")
					// Animation auto-advances frames every 150ms; wait long enough for the
					// gradient to visibly rotate, then snap again. A regression in
					// payload-bracketing would show up as misalignment of the trailing text.
					Thread.sleep(700)
					capturedFiles += session.capture("02-animated")
				}
			}
		}

		check(capturedFiles.isNotEmpty()) { "no screenshots produced" }
		capturedFiles.forEach { file ->
			check(file.length() > 1024) { "screenshot ${file.name} too small (${file.length()} bytes)" }
		}
		println("RawText e2e screenshots:")
		capturedFiles.forEach { println("  ${it.absolutePath}") }
	}
}
