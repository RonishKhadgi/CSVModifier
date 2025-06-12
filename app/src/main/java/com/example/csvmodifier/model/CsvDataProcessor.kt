package com.example.csvmodifier.model // Or your actual package name

import android.util.Log
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException // Ensure this import is present
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZonedDateTime // Import ZonedDateTime for full timestamp support
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

// Enum to represent the user's choice for timestamp incrementing
enum class TimestampIncrementMode {
    DAY_ONLY,
    TIME_ONLY,
    DAY_AND_TIME
}

class CsvDataProcessor {

    private val TAG = "CsvProcessor"
    private val INCREMENT_TAG = "CsvProcessorIncrement"
    private val ROW_DEBUG_TAG = "CsvRowDebug"

    private val datePatternFormatters: List<Pair<String, DateTimeFormatter>> = listOf(
        "MM/dd/yyyy" to DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        "dd/MM/yyyy" to DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        "yyyy-MM-dd" to DateTimeFormatter.ofPattern("yyyy-MM-dd")
    )

    fun readCsvHeader(inputStream: InputStream): Result<List<String>> {
        // This function remains the same
        return try {
            val headerRow: List<String>? = csvReader().open(inputStream) {
                readNext()
            }
            if (headerRow != null && headerRow.isNotEmpty()) { Result.success(headerRow) }
            else { Result.failure(IllegalArgumentException("CSV file appears to be empty or header is missing.")) }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { inputStream.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun processCsvStreaming(
        sourceStream: InputStream,
        destinationStream: OutputStream,
        rowsToAdd: Int,
        dateIncrementStep: Long,      // NEW: Specific step for dates/times
        numberIncrementStep: Long,    // NEW: Specific step for numbers
        targetColumnNames: List<String>,
        uuidColumnNames: Set<String>,
        generateFromFirstRowOnly: Boolean,
        timestampIncrementMode: TimestampIncrementMode
    ): Result<Long> {
        var totalRowsWritten = 0L
        Log.d(TAG, "Starting CSV STREAMING. Date Step: $dateIncrementStep, Number Step: $numberIncrementStep")

        try {
            csvReader().open(sourceStream) {
                csvWriter().open(destinationStream) {
                    val header = readNext() ?: throw IOException("CSV file is empty or missing a header.")
                    writeRow(header)
                    totalRowsWritten++

                    val targetColumnsForIncrementIndices = targetColumnNames.mapNotNull { colName ->
                        header.indexOf(colName.trim()).takeIf { it != -1 }?.let { Pair(colName.trim(), it) }
                    }
                    val uuidColumnIndices = uuidColumnNames.mapNotNull { colName ->
                        header.indexOf(colName.trim()).takeIf { it != -1 }?.let { Pair(colName.trim(), it) }
                    }.associate { it.second to it.first }

                    if (generateFromFirstRowOnly) {
                        val firstDataRow = readNext()
                        if (firstDataRow != null) {
                            writeRow(firstDataRow)
                            totalRowsWritten++
                            for (i in 1..rowsToAdd) {
                                val newRow = createNewRow(firstDataRow, i, dateIncrementStep, numberIncrementStep, uuidColumnIndices, targetColumnsForIncrementIndices, timestampIncrementMode)
                                writeRow(newRow)
                                totalRowsWritten++
                            }
                        }
                    } else {
                        var originalRow = readNext()
                        while (originalRow != null) {
                            val currentRow = originalRow
                            writeRow(currentRow)
                            totalRowsWritten++
                            for (i in 1..rowsToAdd) {
                                val newRow = createNewRow(currentRow, i, dateIncrementStep, numberIncrementStep, uuidColumnIndices, targetColumnsForIncrementIndices, timestampIncrementMode)
                                writeRow(newRow)
                                totalRowsWritten++
                            }
                            originalRow = readNext()
                        }
                    }
                }
            }
            return Result.success(totalRowsWritten)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try {
                sourceStream.close()
                destinationStream.close()
            } catch (e: IOException) { /* ignore */ }
        }
    }

    private fun createNewRow(
        templateRow: List<String>,
        iteration: Int,
        dateIncrementStep: Long,
        numberIncrementStep: Long,
        uuidColumnIndices: Map<Int, String>,
        targetColumnsForIncrementIndices: List<Pair<String, Int>>,
        timestampIncrementMode: TimestampIncrementMode
    ): List<String> {
        val newRow = templateRow.toMutableList()

        uuidColumnIndices.forEach { (targetIndex, _) ->
            if (newRow.size > targetIndex) {
                newRow[targetIndex] = UUID.randomUUID().toString().toUpperCase()
            }
        }

        targetColumnsForIncrementIndices.forEach { (_, targetIndex) ->
            if (!uuidColumnIndices.containsKey(targetIndex) && newRow.size > targetIndex) {
                val cumulativeDateIncrement = dateIncrementStep * iteration
                val cumulativeNumberIncrement = numberIncrementStep * iteration
                newRow[targetIndex] = incrementValue(templateRow[targetIndex], cumulativeDateIncrement, cumulativeNumberIncrement, timestampIncrementMode)
            }
        }
        return newRow
    }

    private fun incrementValue(
        value: String,
        cumulativeDateIncrement: Long,
        cumulativeNumberIncrement: Long,
        timestampIncrementMode: TimestampIncrementMode
    ): String {
        val trimmedValue = value.trim()

        // 1. Try to parse as a full ZonedDateTime
        try {
            val zonedDateTime = ZonedDateTime.parse(trimmedValue)
            val newDateTime = when (timestampIncrementMode) {
                TimestampIncrementMode.DAY_ONLY -> zonedDateTime.plusDays(cumulativeDateIncrement)
                TimestampIncrementMode.TIME_ONLY -> zonedDateTime.plusSeconds(cumulativeDateIncrement)
                TimestampIncrementMode.DAY_AND_TIME -> zonedDateTime.plusDays(cumulativeDateIncrement).plusSeconds(cumulativeDateIncrement)
            }
            return newDateTime.format(DateTimeFormatter.ISO_INSTANT)
        } catch (e: DateTimeParseException) { /* Not a ZonedDateTime */ }

        // 2. Try to parse as a simple Date
        for ((_, formatter) in datePatternFormatters) {
            try {
                val date = LocalDate.parse(trimmedValue, formatter)
                return date.plusDays(cumulativeDateIncrement).format(formatter)
            } catch (e: DateTimeParseException) { /* Try next */ }
        }

        // 3. Try PREFIXNUMBER Increment Logic
        val regex = Regex("^(.*?)(\\d+)$")
        val matchResult = regex.matchEntire(trimmedValue)
        if (matchResult != null) {
            val number = matchResult.groupValues[2].toLongOrNull()
            if (number != null) {
                val prefix = matchResult.groupValues[1]
                val numberPart = matchResult.groupValues[2]
                val newNumber = number + cumulativeNumberIncrement
                return prefix + newNumber.toString().padStart(numberPart.length, '0')
            }
        }

        return trimmedValue
    }
}
