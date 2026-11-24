package com.dailyplan.shared.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

fun openDailyPlanDatabase(context: Context): DailyPlanDatabase =
    Room.databaseBuilder<DailyPlanDatabase>(
        context = context.applicationContext,
        name = context.applicationContext.getDatabasePath("dailyplan.sqlite").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .build()
