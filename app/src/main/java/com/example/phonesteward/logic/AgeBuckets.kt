package com.example.phonesteward.logic

import java.time.Instant
import java.time.ZoneId

/**
 *  age rule: remind at 3 months, trash at 4 months.
 *
 * Boundaries, in local calendar time of [zone]:
 * - age < 3 months            -> UNDER_3_MONTHS
 * - 3 months <= age < 4 months -> MONTHS_3_TO_4 (the reminder bucket)
 * - age >= 4 months           -> OVER_4_MONTHS (the cut-off is reached)
 *
 * `dateTakenMillis` wins; `dateAddedMillis` is the stated fallback when
 * DATE_TAKEN is missing (null or <= 0, as some writers store 0).
 * Missing both is UNKNOWN. Future timestamps count as under three months.
 */
object AgeBuckets {

    fun bucketFor(
        dateTakenMillis: Long?,
        dateAddedMillis: Long?,
        nowMillis: Long,
        zone: ZoneId,
    ): AgeBucket {
        val takenRaw = dateTakenMillis?.takeIf { it > 0 } ?: dateAddedMillis?.takeIf { it > 0 }
            ?: return AgeBucket.UNKNOWN
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val taken = Instant.ofEpochMilli(takenRaw).atZone(zone)
        if (!taken.isBefore(now)) return AgeBucket.UNDER_3_MONTHS
        return when {
            taken.isAfter(now.minusMonths(3)) -> AgeBucket.UNDER_3_MONTHS
            taken.isAfter(now.minusMonths(4)) -> AgeBucket.MONTHS_3_TO_4
            else -> AgeBucket.OVER_4_MONTHS
        }
    }
}
