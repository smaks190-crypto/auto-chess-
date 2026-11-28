package dev.nox.local

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NativeCommandsTest {
    @Test fun exactAlarmAndRepeat() {
        assertEquals(NativeCommand.Alarm(7,30,emptySet(),true), NativeCommands.parse("Разбуди меня завтра в 7:30"))
        assertEquals(NativeCommand.Volume(-1), NativeCommands.parse("Сделай тише"))
        assertEquals(NativeCommand.Alarm(7, 0, emptySet(), true), NativeCommands.parse("Нокс, поставь будильник завтра на семь утра"))
        assertEquals(NativeCommand.Alarm(7, 0, DayOfWeek.values().take(5).toSet(), false),
            NativeCommands.parse("буди меня по будням в семь утра"))
        assertEquals(NativeCommand.Alarm(19, 30, emptySet(), false), NativeCommands.parse("поставь будильник на 7:30 вечера"))
        assertEquals(NativeCommand.Alarm(7,30,emptySet(),false), NativeCommands.parse("поставь будильник на семь тридцать"))
        assertEquals(NativeCommand.Alarm(7,45,emptySet(),false), NativeCommands.parse("поставь будильник на 7 45"))
        assertEquals(NativeCommand.AlarmInvalid, NativeCommands.parse("поставь будильник на 25:00"))
        assertEquals(NativeCommand.AlarmInvalid, NativeCommands.parse("поставь будильник на 7 и открой Telegram"))
        assertNull(NativeCommands.parse("не включай фонарик"))
        assertNull(NativeCommands.parse("включи фонарик и отправь сообщение"))
        assertEquals(NativeCommand.Torch(true), NativeCommands.parse("Нокс, включи фонарик"))
    }
    @Test fun bilingualNativeAliases() {
        for (phrase in listOf("What is my phone battery level?", "Battery level", "Check battery", "How much battery is left?", "Show battery level please",
            "Сколько заряда у моего телефона?", "Сколько заряда", "Уровень заряда", "Какой заряд телефона?", "Пожалуйста, сколько заряда телефона")) {
            assertEquals(phrase, NativeCommand.Battery, NativeCommands.parse(phrase))
        }
        assertEquals(NativeCommand.Time, NativeCommands.parse("What time is it?"))
        assertEquals(NativeCommand.Date, NativeCommands.parse("What is today's date?"))
        assertEquals(NativeCommand.Alarm(7,30,emptySet(),true), NativeCommands.parse("Set an alarm tomorrow at 7:30 am"))
        assertEquals(NativeCommand.Alarm(19,0,emptySet(),false), NativeCommands.parse("Set alarm at 7 pm"))
        assertEquals(NativeCommand.Stopwatch("start"), NativeCommands.parse("Start the stopwatch"))
        assertEquals(NativeCommand.Stopwatch("stop"), NativeCommands.parse("Stop stopwatch"))
        for (phrase in listOf("Don't start stopwatch", "Check battery and send it", "How to set alarm at 7", "Set alarm at 7 and open Telegram"))
            assertNull(NativeCommands.parse(phrase))
    }
    @Test fun nextAlarmUsesLocalDateAndFutureOnly() {
        val now = ZonedDateTime.of(2026,11,28,12,20,0,0,ZoneId.of("Europe/Moscow"))
        assertEquals(now.toLocalDate().plusDays(1), NativeCommands.nextAlarm(NativeCommand.Alarm(7,0,emptySet(),false),now).toLocalDate())
        assertEquals(DayOfWeek.MONDAY, NativeCommands.nextAlarm(NativeCommand.Alarm(7,0,DayOfWeek.values().take(5).toSet(),false),now).dayOfWeek)
        assertEquals(now.toLocalDate(), NativeCommands.nextAlarm(NativeCommand.Alarm(19,0,emptySet(),false),now).toLocalDate())
    }
}
