package com.personaledge.core.diagnostics

/** Result of exporting the app's bounded, content-free diagnostic event stream. */
sealed interface DiagnosticExportResult {
    data class Success(
        val sourceFileCount: Int,
        val byteCount: Long,
    ) : DiagnosticExportResult

    /** This recorder has no backing store, as with the fail-open no-op recorder. */
    data object Unavailable : DiagnosticExportResult

    /** A source was oversized, malformed, linked, outside the fixed allowlist, or unreadable. */
    data object SourceRejected : DiagnosticExportResult

    /** The destination failed after the secure source snapshot had been completed. */
    data object DestinationFailed : DiagnosticExportResult
}
