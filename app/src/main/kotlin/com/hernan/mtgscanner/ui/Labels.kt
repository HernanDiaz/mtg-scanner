package com.hernan.mtgscanner.ui

import androidx.annotation.StringRes
import com.hernan.mtgscanner.R
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.PriceKind

// Textos de los enums del código común (que no conoce los recursos de Android).

@get:StringRes
val PriceKind.label: Int
    get() = when (this) {
        PriceKind.TREND -> R.string.price_trend
        PriceKind.LOW -> R.string.price_low
        PriceKind.AVG30 -> R.string.price_avg30
    }

@get:StringRes
val PriceKind.description: Int
    get() = when (this) {
        PriceKind.TREND -> R.string.price_trend_desc
        PriceKind.LOW -> R.string.price_low_desc
        PriceKind.AVG30 -> R.string.price_avg30_desc
    }

@get:StringRes
val CollectionExport.Format.label: Int
    get() = when (this) {
        CollectionExport.Format.CSV -> R.string.format_csv
        CollectionExport.Format.MOXFIELD -> R.string.format_moxfield
        CollectionExport.Format.TEXT -> R.string.format_text
    }
