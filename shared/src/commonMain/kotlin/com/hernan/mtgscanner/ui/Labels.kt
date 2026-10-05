package com.hernan.mtgscanner.ui

import org.jetbrains.compose.resources.StringResource
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.PriceKind

// Textos de los enums del código común (que no conoce los recursos de Android).

val PriceKind.label: StringResource
    get() = when (this) {
        PriceKind.TREND -> Res.string.price_trend
        PriceKind.LOW -> Res.string.price_low
        PriceKind.AVG30 -> Res.string.price_avg30
    }

val PriceKind.description: StringResource
    get() = when (this) {
        PriceKind.TREND -> Res.string.price_trend_desc
        PriceKind.LOW -> Res.string.price_low_desc
        PriceKind.AVG30 -> Res.string.price_avg30_desc
    }

val CollectionExport.Format.label: StringResource
    get() = when (this) {
        CollectionExport.Format.CSV -> Res.string.format_csv
        CollectionExport.Format.MOXFIELD -> Res.string.format_moxfield
        CollectionExport.Format.TEXT -> Res.string.format_text
    }
