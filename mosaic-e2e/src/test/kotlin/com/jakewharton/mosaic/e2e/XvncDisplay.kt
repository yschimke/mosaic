package com.jakewharton.mosaic.e2e

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Starts and stops an Xvnc (TigerVNC's `Xvnc`) virtual X server for the lifetime of the test.
 *
 * Xvnc is preferred over Xvfb because the consumer asked for it specifically — it produces the
 * same screenshot-friendly behaviour (a real X11 root window we can drive with `xdotool` and
 * snapshot with `import`) but also exposes a VNC port if a human wants to peek at a running
 * test mid-run.
 *
 * The harness picks a free display number in `:90..:120`, screens any existing lock files /
 * sockets, and races a TCP bind on `5900 + displayNumber` to confirm the slot is free even if
 * the lockfile check misses. On `close()` the Xvnc process is force-terminated.
 */
internal class XvncDisplay : AutoCloseable {

	val displayNumber: Int = pickDisplayNumber()
	val display: String = ":$displayNumber"

	private var process: Process? = null

	fun start(width: Int = 1280, height: Int = 800, depth: Int = 24) {
		val logFile = File("/tmp/xvnc-mosaic-$displayNumber.log")
		val cmd = listOf(
			"Xvnc",
			display,
			"-geometry", "${width}x$height",
			"-depth", depth.toString(),
			"-SecurityTypes", "None",
			"-AlwaysShared",
		)
		process = ProcessBuilder(cmd)
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
			.start()

		// Xvnc creates `/tmp/.X{displayNumber}-lock` once it has the slot. Polling that is the
		// cheapest way to know it's actually serving — `sleep` based "wait a bit" loses races
		// on slow CI runners. 5s is a generous ceiling; success usually hits in <300ms.
		val lock = File("/tmp/.X$displayNumber-lock")
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (System.nanoTime() < deadline) {
			if (lock.exists()) return
			if (process!!.waitFor(100, TimeUnit.MILLISECONDS)) {
				val tail = logFile.readText().takeLast(800)
				error("Xvnc on $display exited before ready (rc=${process!!.exitValue()}). Tail:\n$tail")
			}
		}
		error("Xvnc on $display did not become ready within 5s. Log: $logFile")
	}

	override fun close() {
		val p = process ?: return
		p.destroy()
		if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
		process = null
	}

	private fun pickDisplayNumber(): Int {
		// :0..:89 are reserved for desktop sessions, the ssh forward range, etc. :90+ is a
		// conventional "throwaway" slot range.
		for (n in 90..120) {
			val lock = File("/tmp/.X$n-lock")
			val sock = File("/tmp/.X11-unix/X$n")
			if (lock.exists() || sock.exists()) continue
			// VNC servers bind 5900 + n. A free port here is the second confirmation.
			try {
				ServerSocket(5900 + n).use { /* port was free; close it again */ }
				return n
			} catch (_: Throwable) {
				// busy — try next
			}
		}
		error("No free X display in :90..:120")
	}
}
