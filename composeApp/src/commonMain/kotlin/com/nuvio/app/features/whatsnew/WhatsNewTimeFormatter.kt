package com.nuvio.app.features.whatsnew

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class LastCheckedUnit {
    JUST_NOW,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

internal data class LastCheckedAge(
    val amount: Long,
    val unit: LastCheckedUnit,
)

internal fun calculateLastCheckedAge(
    fetchedAtMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): LastCheckedAge {
    val effectiveNowMillis = nowMillis.coerceAtLeast(fetchedAtMillis)
    val elapsedMillis = effectiveNowMillis - fetchedAtMillis

    val minuteMillis = 60_000L
    val hourMillis = 60L * minuteMillis
    val dayMillis = 24L * hourMillis

    if (elapsedMillis < minuteMillis) {
        return LastCheckedAge(amount = 0L, unit = LastCheckedUnit.JUST_NOW)
    }

    if (elapsedMillis < hourMillis) {
        return LastCheckedAge(
            amount = elapsedMillis / minuteMillis,
            unit = LastCheckedUnit.MINUTE,
        )
    }

    val start = Instant.fromEpochMilliseconds(fetchedAtMillis)
    val end = Instant.fromEpochMilliseconds(effectiveNowMillis)
    val calendarPeriod = start.periodUntil(end, timeZone)
    // 31 Jan to 28 Feb is a calendar month even though February has no 31st.
    val endOfMonthMonths = endOfMonthSpan(start, end, timeZone)

    return when {
        calendarPeriod.years >= 1 -> LastCheckedAge(
            amount = calendarPeriod.years.toLong(),
            unit = LastCheckedUnit.YEAR,
        )
        calendarPeriod.months >= 1 -> LastCheckedAge(
            amount = calendarPeriod.months.toLong(),
            unit = LastCheckedUnit.MONTH,
        )
        endOfMonthMonths != null -> LastCheckedAge(
            amount = endOfMonthMonths,
            unit = LastCheckedUnit.MONTH,
        )
        calendarPeriod.days >= 7 -> LastCheckedAge(
            amount = (calendarPeriod.days / 7L),
            unit = LastCheckedUnit.WEEK,
        )
        calendarPeriod.days >= 1 -> LastCheckedAge(
            amount = calendarPeriod.days.toLong(),
            unit = LastCheckedUnit.DAY,
        )
        elapsedMillis >= dayMillis -> LastCheckedAge(
            amount = elapsedMillis / dayMillis,
            unit = LastCheckedUnit.DAY,
        )
        else -> LastCheckedAge(
            amount = elapsedMillis / hourMillis,
            unit = LastCheckedUnit.HOUR,
        )
    }
}

private fun endOfMonthSpan(start: Instant, end: Instant, timeZone: TimeZone): Long? {
    val startDate = start.toLocalDateTime(timeZone).date
    val endDate = end.toLocalDateTime(timeZone).date
    if (!startDate.isLastDayOfMonth() || !endDate.isLastDayOfMonth()) return null
    val months = (endDate.year - startDate.year) * 12L + (endDate.monthNumber - startDate.monthNumber)
    return months.takeIf { it >= 1L }
}

private fun kotlinx.datetime.LocalDate.isLastDayOfMonth(): Boolean =
    plus(DatePeriod(days = 1)).month != month
