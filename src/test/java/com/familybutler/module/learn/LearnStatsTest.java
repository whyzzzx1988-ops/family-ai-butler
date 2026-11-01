package com.familybutler.module.learn;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 学习星图统计的纯函数口径：连续天数与近 N 日分桶（服务层 stats 的可复核部分）。 */
class LearnStatsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    @Test
    void streakEmptyIsZero() {
        assertEquals(0, LearnService.computeStreak(Set.of(), TODAY));
    }

    @Test
    void streakCountsBackFromToday() {
        Set<LocalDate> days = Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(4));
        assertEquals(3, LearnService.computeStreak(days, TODAY));
    }

    @Test
    void streakMayEndYesterday() {
        Set<LocalDate> days = Set.of(TODAY.minusDays(1), TODAY.minusDays(2));
        assertEquals(2, LearnService.computeStreak(days, TODAY));
    }

    @Test
    void streakBrokenWhenYesterdayMissing() {
        Set<LocalDate> days = Set.of(TODAY.minusDays(2), TODAY.minusDays(3));
        assertEquals(0, LearnService.computeStreak(days, TODAY));
    }

    @Test
    void countByDayFillsZerosOldestFirst() {
        List<LocalDate> days = List.of(TODAY, TODAY, TODAY.minusDays(3), TODAY.minusDays(9));
        List<LearnService.DayCount> out = LearnService.countByDay(days, TODAY, 7);
        assertEquals(7, out.size());
        assertEquals(TODAY.minusDays(6).toString(), out.get(0).date());
        assertEquals(1, out.get(3).count()); // 三天前 1 次
        assertEquals(2, out.get(6).count()); // 今天 2 次；9 天前在窗口外不计
        assertEquals(3, out.stream().mapToLong(LearnService.DayCount::count).sum());
    }
}
