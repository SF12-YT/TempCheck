package com.example.tempalearn

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object AppPreferences {
    const val PREFS_NAME="templearn_preferences"
    const val KEY_LOCATION="location"
    const val KEY_ACTIVE_LOCATION_KEY="active_location_key"
    const val DEFAULT_LOCATION="2780, NSW, Australia"
    const val SHARED_MODEL_KEY="__templearn_shared_weather_model__"
    const val KEY_NOTIFICATION_PERMISSION_REQUESTED="notification_permission_requested"
    fun locationKey(location:String)=location.trim().replace(Regex("\\s+")," ").lowercase(Locale.US)
    fun geocodedLocationKey(geocodingId:Long)="geo:$geocodingId"
    fun activeLocationKey(preferences:android.content.SharedPreferences,location:String)=
        preferences.getString(KEY_ACTIVE_LOCATION_KEY,null)?.takeIf{it.isNotBlank()} ?: locationKey(location)
    fun syncTimeKey(locationKey:String)="last_sync_$locationKey"
    fun timezoneKey(locationKey:String)="timezone_$locationKey"
    fun resolvedAddressKey(locationKey:String)="resolved_address_$locationKey"
    fun latitudeKey(locationKey:String)="latitude_$locationKey"
    fun longitudeKey(locationKey:String)="longitude_$locationKey"
    fun elevationKey(locationKey:String)="elevation_$locationKey"
    fun historyCompleteKey(locationKey:String)="open_meteo_history_complete_$locationKey"
}

data class SyncResult(
    val locationKey:String,val fetchedHours:Int,val savedReadings:Int,val trainingExamples:Int,
    val timezoneId:String,val modelReady:Boolean,val resolvedAddress:String,val locationCount:Int,
    val sharedTrainingExamples:Int,val initialHistoryComplete:Boolean,val initialImportCompletedNow:Boolean,
    val importedFromDate:String,val importedToDate:String,val locationProfile:LocationProfile
)

object SyncRepository {
    /** First sync imports since 2000-01-01; later syncs re-request only the latest local date. */
    @Throws(Exception::class)
    fun sync(context:Context,location:String,selectedProfile:LocationProfile?=null):SyncResult{
        val app=context.applicationContext
        val normalized=location.trim()
        require(normalized.length>=2){"Enter a postcode, town, or address."}
        val prefs=app.getSharedPreferences(AppPreferences.PREFS_NAME,Context.MODE_PRIVATE)
        val legacyKey=AppPreferences.locationKey(normalized)
        var locationKey=AppPreferences.activeLocationKey(prefs,normalized)
        val db=TemperatureDatabase(app)
        try{
            var profile=selectedProfile?.copy(searchQuery=normalized)
                ?: db.getLocationProfile(locationKey)
                ?: db.getLocationProfile(legacyKey)
                ?: LocationGeocodingClient.resolveUnambiguous(normalized,legacyKey)
            val canonical=AppPreferences.geocodedLocationKey(profile.geocodingId)
            if(locationKey!=canonical){
                db.moveLocationKey(legacyKey,canonical,profile.geocodingId)
                locationKey=canonical
                profile=profile.copy(locationKey=canonical)
            }
            if(profile.elevationM==null){
                val elevation=runCatching{OpenMeteoClient.fetchElevation(profile.latitude,profile.longitude)}.getOrNull()
                if(elevation!=null)profile=profile.copy(elevationM=elevation)
            }
            db.saveLocationProfile(profile)
            val zone=ZoneId.of(profile.timezoneId)
            val today=LocalDate.now(zone)
            val completeKey=AppPreferences.historyCompleteKey(locationKey)
            val initialNeeded=!prefs.getBoolean(completeKey,false)
            val latest=db.getLatestTimestampSec(locationKey)
            val latestDate=latest?.let{Instant.ofEpochSecond(it).atZone(zone).toLocalDate()}
            val start=latestDate ?: if(initialNeeded)LocalDate.of(2000,1,1) else today
            val imported=OpenMeteoClient.fetchHourlyArchive(
                profile.latitude,profile.longitude,profile.timezoneId,profile.elevationM,start,today
            ){batch->db.insertReadings(locationKey,batch)}
            // Only set after the entire response parsed. A partial stream resumes from the last saved date.
            if(initialNeeded)prefs.edit().putBoolean(completeKey,true).apply()

            val sampled=db.getAllReadingsByLocation()
            val profiles=db.getAllLocationProfiles()
            val series=linkedMapOf<String,Pair<List<HourlyTemperature>,String>>()
            val contexts=linkedMapOf<String,ModelLocationContext>()
            sampled.forEach{(key,readings)->
                val saved=profiles[key] ?: if(key==locationKey)profile else null
                if(saved!=null){series[key]=readings to saved.timezoneId;contexts[key]=saved.modelContext()}
            }
            contexts[locationKey]=profile.modelContext()
            val otherSeries=series.filterKeys{it!=locationKey}
            val prior=if(otherSeries.isNotEmpty())WeatherLearner.trainShared(
                otherSeries,locationContexts=contexts.filterKeys{it!=locationKey}
            )else null
            val local=WeatherLearner.trainLocal(
                readings=series[locationKey]?.first.orEmpty(),
                timezoneId=profile.timezoneId,priorModel=prior,locationContext=profile.modelContext()
            )
            if(local!=null)db.saveModel(locationKey,local)
            val shared=WeatherLearner.trainShared(series,locationContexts=contexts)
            if(shared!=null)db.saveModel(AppPreferences.SHARED_MODEL_KEY,shared)
            prefs.edit()
                .putString(AppPreferences.KEY_ACTIVE_LOCATION_KEY,locationKey)
                .putString(AppPreferences.timezoneKey(locationKey),profile.timezoneId)
                .putString(AppPreferences.resolvedAddressKey(locationKey),profile.displayName)
                .putString(AppPreferences.latitudeKey(locationKey),profile.latitude.toString())
                .putString(AppPreferences.longitudeKey(locationKey),profile.longitude.toString())
                .putString(AppPreferences.elevationKey(locationKey),profile.elevationM?.toString())
                .putLong(AppPreferences.syncTimeKey(locationKey),Instant.now().epochSecond).apply()
            return SyncResult(
                locationKey=locationKey,fetchedHours=imported,savedReadings=db.getSavedCount(locationKey),
                trainingExamples=local?.trainingExamples ?: 0,timezoneId=profile.timezoneId,
                modelReady=local!=null,resolvedAddress=profile.displayName,locationCount=db.getLocationCount(),
                sharedTrainingExamples=shared?.trainingExamples ?: 0,
                initialHistoryComplete=prefs.getBoolean(completeKey,false),initialImportCompletedNow=initialNeeded,
                importedFromDate=start.toString(),importedToDate=today.toString(),locationProfile=profile
            )
        }finally{db.close()}
    }
}