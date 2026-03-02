package com.browsermover.app.model

sealed class MigrationResult {

    data class Success(
        val summary: String,
        val warnings: List<String> = emptyList(),
        val logPath: String = ""
    ) : MigrationResult()

    data class Partial(
        val message: String,
        val successItems: List<String>,
        val failedItems: List<String>,
        val logPath: String = ""
    ) : MigrationResult()

    data class Failure(
        val error: String,
        val technicalDetail: String = "",
        val logPath: String = ""
    ) : MigrationResult()

    data class Progress(
        val phase: Int,
        val phaseName: String,
        val detail: String,
        val progressPercent: Int
    ) : MigrationResult()
}
