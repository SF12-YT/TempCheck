package com.example.tempalearn

/** One canonical place record shared by all weather-provider adapters and the learning model. */
data class LocationProfile(
    val locationKey: String,
    val geocodingId: Long,
    val searchQuery: String,
    val name: String,
    val admin1: String?,
    val admin2: String?,
    val country: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double?,
    val timezoneId: String,
    val featureCode: String?,
    val population: Long?,
    val postcodes: List<String>,
    val resolvedAtSec: Long
) {
    val displayName: String
        get() = listOfNotNull(
            name.takeIf { it.isNotBlank() },
            admin2?.takeIf { it.isNotBlank() && it != name },
            admin1?.takeIf { it.isNotBlank() && it != name },
            country?.takeIf { it.isNotBlank() }
        ).distinct().joinToString(", ")

    /** A compact place/admin-area label for provider adapters and human-readable metadata. */
    val placeAndAdminQuery: String
        get() = listOfNotNull(name.takeIf { it.isNotBlank() }, admin1?.takeIf { it.isNotBlank() })
            .distinct().joinToString(", ")

    fun modelContext(): ModelLocationContext = ModelLocationContext(
        latitude = latitude,
        longitude = longitude,
        elevationM = elevationM
    )
}
