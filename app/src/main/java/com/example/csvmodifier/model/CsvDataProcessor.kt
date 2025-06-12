package com.example.csvmodifier.model // Or your actual package name

import android.util.Log
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException // Ensure this import is present
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random

// Enum to represent the user's choice for timestamp incrementing
enum class TimestampIncrementMode {
    DAY_ONLY,
    TIME_ONLY,
    DAY_AND_TIME
}

class CsvDataProcessor {

    private val TAG = "CsvProcessor"
    private val INCREMENT_TAG = "CsvProcessorIncrement"
    private val RANDOM_TAG = "CsvRandomizer"

    private val datePatternFormatters: List<Pair<String, DateTimeFormatter>> = listOf(
        "MM/dd/yyyy" to DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        "dd/MM/yyyy" to DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        "yyyy-MM-dd" to DateTimeFormatter.ofPattern("yyyy-MM-dd")
    )

    /**
     * NEW: Counts the number of data rows in a CSV file.
     */
    fun countRows(sourceStream: InputStream): Result<Int> {
        var rowCount = 0
        return try {
            csvReader().open(sourceStream) {
                // Subtract 1 for the header if it exists, so we only count data rows
                if (readNext() == null) {
                    return@open // Empty file
                }
                while(readNext() != null) {
                    rowCount++
                }
            }
            Log.d(TAG, "Counted $rowCount data rows.")
            Result.success(rowCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count rows", e)
            Result.failure(e)
        } finally {
            try { sourceStream.close() } catch (e: IOException) { /* ignore */ }
        }
    }

    fun readCsvHeader(inputStream: InputStream): Result<List<String>> {
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
        dateIncrementStep: Long,
        numberIncrementStep: Long,
        incrementColumnNames: List<String>,
        uuidColumnNames: Set<String>,
        randomizeColumnNames: Set<String>,
        generateFromFirstRowOnly: Boolean,
        timestampIncrementMode: TimestampIncrementMode,
        onProgress: (Int) -> Unit // NEW: Callback to report progress
    ): Result<Long> {
        var totalRowsWritten = 0L
        Log.d(TAG, "Starting CSV STREAMING. Randomize Columns: ${randomizeColumnNames.joinToString()}")

        try {
            csvReader().open(sourceStream) {
                csvWriter().open(destinationStream) {
                    val header = readNext() ?: throw IOException("CSV file is empty or missing a header.")
                    writeRow(header)
                    totalRowsWritten++

                    val incrementIndices = incrementColumnNames.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 }?.let { Pair(name.trim(), it) } }
                    val uuidIndices = uuidColumnNames.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 } }.toSet()
                    val randomizeIndices = randomizeColumnNames.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 } }.toSet()

                    if (generateFromFirstRowOnly) {
                        val firstDataRow = readNext()
                        if (firstDataRow != null) {
                            writeRow(firstDataRow)
                            totalRowsWritten++
                            for (i in 1..rowsToAdd) {
                                onProgress(i) // Report progress based on generated rows
                                val newRow = createNewRow(firstDataRow, i, dateIncrementStep, numberIncrementStep, incrementIndices, uuidIndices, randomizeIndices, timestampIncrementMode)
                                writeRow(newRow)
                                totalRowsWritten++
                            }
                        }
                    } else {
                        var originalRow = readNext()
                        var originalRowIndex = 0
                        while (originalRow != null) {
                            originalRowIndex++
                            onProgress(originalRowIndex) // Report progress based on processed source rows
                            val currentRow = originalRow
                            writeRow(currentRow)
                            totalRowsWritten++
                            for (i in 1..rowsToAdd) {
                                val newRow = createNewRow(currentRow, i, dateIncrementStep, numberIncrementStep, incrementIndices, uuidIndices, randomizeIndices, timestampIncrementMode)
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
        incrementIndices: List<Pair<String, Int>>,
        uuidIndices: Set<Int>,
        randomizeIndices: Set<Int>,
        timestampIncrementMode: TimestampIncrementMode
    ): List<String> {
        val newRow = templateRow.toMutableList()

        for (i in newRow.indices) {
            when {
                randomizeIndices.contains(i) -> { newRow[i] = randomizeValue(templateRow[i]) }
                uuidIndices.contains(i) -> { newRow[i] = UUID.randomUUID().toString().toUpperCase() }
                incrementIndices.any { it.second == i } -> {
                    val cumulativeDateIncrement = dateIncrementStep * iteration
                    val cumulativeNumberIncrement = numberIncrementStep * iteration
                    newRow[i] = incrementValue(templateRow[i], cumulativeDateIncrement, cumulativeNumberIncrement, timestampIncrementMode)
                }
            }
        }
        return newRow
    }

    private fun randomizeValue(originalValue: String): String {
        val trimmedValue = originalValue.trim()
        if (trimmedValue.equals("true", ignoreCase = true) || trimmedValue.equals("false", ignoreCase = true)) {
            return Random.nextBoolean().toString()
        }
        try {
            val originalDate = ZonedDateTime.parse(trimmedValue)
            val randomDays = ThreadLocalRandom.current().nextLong(-365, 366)
            val randomSeconds = ThreadLocalRandom.current().nextLong(-86400, 86401)
            return originalDate.plusDays(randomDays).plusSeconds(randomSeconds).format(DateTimeFormatter.ISO_INSTANT)
        } catch (e: DateTimeParseException) { /* Not a timestamp */ }
        for ((_, formatter) in datePatternFormatters) {
            try {
                val originalDate = LocalDate.parse(trimmedValue, formatter)
                val randomDays = ThreadLocalRandom.current().nextLong(-365, 366)
                return originalDate.plusDays(randomDays).format(formatter)
            } catch (e: DateTimeParseException) { /* Try next */ }
        }
        val regex = Regex("^(.*?)(\\d+)$")
        val matchResult = regex.matchEntire(trimmedValue)
        if (matchResult != null) {
            val prefix = matchResult.groupValues[1]
            val numberPart = matchResult.groupValues[2]
            val maxRandom = "9".repeat(numberPart.length).toLongOrNull() ?: Long.MAX_VALUE
            val randomNumber = ThreadLocalRandom.current().nextLong(0, maxRandom + 1)
            return prefix + randomNumber.toString().padStart(numberPart.length, '0')
        }
        return originalValue
    }

    private fun incrementValue(
        value: String,
        cumulativeDateIncrement: Long,
        cumulativeNumberIncrement: Long,
        timestampIncrementMode: TimestampIncrementMode
    ): String {
        val trimmedValue = value.trim()
        try {
            val zonedDateTime = ZonedDateTime.parse(trimmedValue)
            val newDateTime = when (timestampIncrementMode) {
                TimestampIncrementMode.DAY_ONLY -> zonedDateTime.plusDays(cumulativeDateIncrement)
                TimestampIncrementMode.TIME_ONLY -> zonedDateTime.plusSeconds(cumulativeDateIncrement)
                TimestampIncrementMode.DAY_AND_TIME -> zonedDateTime.plusDays(cumulativeDateIncrement).plusSeconds(cumulativeDateIncrement)
            }
            return newDateTime.format(DateTimeFormatter.ISO_INSTANT)
        } catch (e: DateTimeParseException) { /* Not a ZonedDateTime */ }
        for ((_, formatter) in datePatternFormatters) {
            try {
                val date = LocalDate.parse(trimmedValue, formatter)
                return date.plusDays(cumulativeDateIncrement).format(formatter)
            } catch (e: DateTimeParseException) { /* Try next */ }
        }
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
