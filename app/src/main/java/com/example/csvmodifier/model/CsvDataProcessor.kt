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

    private val ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val datePatternFormatters: List<Pair<String, DateTimeFormatter>> = listOf(
        "MM/dd/yyyy" to DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        "dd/MM/yyyy" to DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        "yyyy-MM-dd" to DateTimeFormatter.ofPattern("yyyy-MM-dd")
    )

    fun countRows(sourceStream: InputStream): Result<Int> {
        var rowCount = 0
        return try {
            csvReader().open(sourceStream) {
                if (readNext() == null) { return@open }
                while(readNext() != null) { rowCount++ }
            }
            Result.success(rowCount)
        } catch (e: Exception) { Result.failure(e) }
        finally { try { sourceStream.close() } catch (e: IOException) { /* ignore */ } }
    }

    fun readCsvHeader(inputStream: InputStream): Result<List<String>> {
        return try {
            val headerRow: List<String>? = csvReader().open(inputStream) { readNext() }
            if (headerRow != null && headerRow.isNotEmpty()) { Result.success(headerRow) }
            else { Result.failure(IllegalArgumentException("CSV file appears to be empty or header is missing.")) }
        } catch (e: Exception) { Result.failure(e) }
        finally { try { inputStream.close() } catch (e: Exception) { /* ignore */ } }
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
        valueFromListMap: Map<String, List<String>>, // NEW
        generateFromFirstRowOnly: Boolean,
        timestampIncrementMode: TimestampIncrementMode,
        onProgress: (Int) -> Unit
    ): Result<Long> {
        var totalRowsWritten = 0L

        try {
            csvReader().open(sourceStream) {
                csvWriter().open(destinationStream) {
                    val header = readNext() ?: throw IOException("CSV file is empty or missing a header.")
                    writeRow(header)
                    totalRowsWritten++

                    val incrementIndices = incrementColumnNames.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 }?.let { Pair(name.trim(), it) } }
                    val uuidIndices = uuidColumnNames.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 } }.toSet()
                    val randomizeIndices = randomizeColumnNames.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 } }.toSet()
                    val listValueIndices = valueFromListMap.keys.mapNotNull { name -> header.indexOf(name.trim()).takeIf { it != -1 }?.let { Pair(it, valueFromListMap[name]!!) } }.toMap()

                    if (generateFromFirstRowOnly) {
                        val firstDataRow = readNext()
                        if (firstDataRow != null) {
                            writeRow(firstDataRow)
                            totalRowsWritten++
                            for (i in 1..rowsToAdd) {
                                onProgress(i)
                                val newRow = createNewRow(firstDataRow, i, dateIncrementStep, numberIncrementStep, incrementIndices, uuidIndices, randomizeIndices, listValueIndices, timestampIncrementMode)
                                writeRow(newRow)
                                totalRowsWritten++
                            }
                        }
                    } else {
                        var originalRow = readNext()
                        var originalRowIndex = 0
                        while (originalRow != null) {
                            originalRowIndex++
                            onProgress(originalRowIndex)
                            val currentRow = originalRow
                            writeRow(currentRow)
                            totalRowsWritten++
                            for (i in 1..rowsToAdd) {
                                val newRow = createNewRow(currentRow, i, dateIncrementStep, numberIncrementStep, incrementIndices, uuidIndices, randomizeIndices, listValueIndices, timestampIncrementMode)
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
        listValueIndices: Map<Int, List<String>>, // NEW
        timestampIncrementMode: TimestampIncrementMode
    ): List<String> {
        val newRow = templateRow.toMutableList()

        for (i in newRow.indices) {
            when {
                // Priority: 1. List, 2. Randomize, 3. UUID, 4. Increment
                listValueIndices.containsKey(i) -> {
                    newRow[i] = listValueIndices[i]?.random() ?: templateRow[i]
                }
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
            val randomDays = Random.Default.nextLong(-365, 366)
            val randomSeconds = Random.Default.nextLong(-86400, 86401)
            return originalDate.plusDays(randomDays).plusSeconds(randomSeconds).format(DateTimeFormatter.ISO_INSTANT)
        } catch (e: DateTimeParseException) { /* Not a timestamp */ }
        for ((_, formatter) in datePatternFormatters) {
            try {
                val originalDate = LocalDate.parse(trimmedValue, formatter)
                val randomDays = Random.Default.nextLong(-365, 366)
                return originalDate.plusDays(randomDays).format(formatter)
            } catch (e: DateTimeParseException) { /* Try next */ }
        }
        val randomizedString = trimmedValue.map { char ->
            when {
                char.isLetter() -> ALPHANUMERIC_CHARS.random()
                char.isDigit() -> ALPHANUMERIC_CHARS.random()
                else -> char
            }
        }.joinToString("")
        return randomizedString
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
