package fm.heterodyne.app.ui

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

/** All methods and callbacks run on the dedicated probe Handler, outside the audio path. */
class AndroidEnvironmentProbe(
    private val context: Context,
    private val handler: Handler,
    private val uiMode: () -> Int,
) : EnvironmentProbeSource {
    private var sensors: SensorManager? = null
    private var locations: LocationManager? = null
    private var light: ProbeReading<Float> = ProbeReading.Unavailable("нет датчика TYPE_LIGHT")
    private var gps: Location? = null
    private var gpsListening = false
    private var gpsFailure: String? = null
    private var car: Any? = null
    private var carClass: Class<*>? = null
    private var carManagerClass: Class<*>? = null
    private var carFailure = "нет класса android.car.Car"
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            event.values.firstOrNull()?.let { light = finite(it, "датчик вернул неверное значение") }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { gps = Location(location) }
        override fun onProviderDisabled(provider: String) { gps = null }
        override fun onProviderEnabled(provider: String) = Unit
        @Deprecated("Required on API 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun start() {
        sensors = context.getSystemService(SensorManager::class.java)
        light = readSafely {
            val manager = sensors ?: return@readSafely ProbeReading.Unavailable("нет сервиса SensorManager")
            val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
                ?: return@readSafely ProbeReading.Unavailable("нет датчика TYPE_LIGHT")
            if (manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler))
                ProbeReading.Unavailable("датчик есть, ожидание значения")
            else ProbeReading.Unavailable("датчик есть, подписка отклонена")
        }
        locations = context.getSystemService(LocationManager::class.java)
        try {
            carManagerClass = Class.forName("android.car.hardware.property.CarPropertyManager")
            val type = Class.forName("android.car.Car").also { carClass = it }
            car = type.getMethod("createCar", Context::class.java).invoke(null, context)
            if (car == null) carFailure = "нет сервиса Car"
        } catch (failure: Exception) { carFailure = reason(failure) }
        catch (_: LinkageError) { carFailure = "библиотека android.car недоступна" }
    }

    override fun read() = ProbeValues(
        uiMode(),
        readSafely {
            context.getSystemService(UiModeManager::class.java)?.let {
                ProbeReading.Value(it.nightMode to it.currentModeType)
            } ?: ProbeReading.Unavailable("нет сервиса UiModeManager")
        },
        light,
        context.packageManager.hasSystemFeature("android.hardware.type.automotive"),
        readCarSpeed(),
        readGpsSpeed(),
    )

    private fun readCarSpeed(): ProbeReading<Float> = readSafely {
        val instance = car ?: return@readSafely ProbeReading.Unavailable(carFailure)
        val type = carClass ?: return@readSafely ProbeReading.Unavailable("нет класса Car")
        val managerType = carManagerClass ?: return@readSafely ProbeReading.Unavailable("нет класса CarPropertyManager")
        if (type.getMethod("isConnected").invoke(instance) != true)
            return@readSafely ProbeReading.Unavailable("сервис Car не подключён")
        val service = type.getField("PROPERTY_SERVICE").get(null) as String
        val manager = type.getMethod("getCarManager", String::class.java).invoke(instance, service)
            ?: return@readSafely ProbeReading.Unavailable("нет сервиса CarPropertyManager")
        val id = Class.forName("android.car.VehiclePropertyIds").getField("PERF_VEHICLE_SPEED").getInt(null)
        val property = managerType.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, id, 0) ?: return@readSafely ProbeReading.Unavailable("PERF_VEHICLE_SPEED не поддерживается")
        val propertyType = property.javaClass
        val status = propertyType.getMethod("getStatus").invoke(property) as Int
        if (status != 0) return@readSafely ProbeReading.Unavailable("PERF_VEHICLE_SPEED недоступно, status=$status")
        val speed = propertyType.getMethod("getValue").invoke(property) as? Number
            ?: return@readSafely ProbeReading.Unavailable("PERF_VEHICLE_SPEED: нет значения")
        finite(speed.toFloat(), "PERF_VEHICLE_SPEED: неверное значение")
    }

    @android.annotation.SuppressLint("MissingPermission") // Explicit check; SecurityException is handled for revocation races.
    private fun readGpsSpeed(): ProbeReading<Float> = readSafely {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopGps()
            return@readSafely ProbeReading.Unavailable("нет разрешения")
        }
        val manager = locations ?: return@readSafely ProbeReading.Unavailable("нет сервиса LocationManager")
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER))
            return@readSafely ProbeReading.Unavailable("нет GPS_PROVIDER")
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gps = null
            return@readSafely ProbeReading.Unavailable("GPS_PROVIDER выключен")
        }
        if (!gpsListening) {
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, handler.looper)
                gpsListening = true
                gpsFailure = null
            } catch (failure: Exception) { gpsFailure = reason(failure) }
        }
        gpsFailure?.let { return@readSafely ProbeReading.Unavailable(it) }
        val location = gps ?: return@readSafely ProbeReading.Unavailable("ожидание GPS")
        if (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 30_000_000_000L)
            return@readSafely ProbeReading.Unavailable("данные GPS устарели")
        if (!location.hasSpeed()) return@readSafely ProbeReading.Unavailable("GPS не сообщил скорость")
        finite(location.speed, "GPS вернул неверную скорость")
    }

    private fun stopGps() {
        if (gpsListening) try { locations?.removeUpdates(locationListener) } catch (_: Exception) { /* permission revoked */ }
        gpsListening = false
        gps = null
        gpsFailure = null
    }

    override fun stop() {
        sensors?.unregisterListener(sensorListener)
        sensors = null
        stopGps()
        locations = null
        car?.let { try { carClass?.getMethod("disconnect")?.invoke(it) } catch (_: Exception) { /* service gone */ } }
        car = null
        carClass = null
        carManagerClass = null
    }

    private fun finite(value: Float, reason: String): ProbeReading<Float> =
        if (value.isFinite()) ProbeReading.Value(value) else ProbeReading.Unavailable(reason)

    private fun <T> readSafely(read: () -> ProbeReading<T>): ProbeReading<T> = try { read() }
        catch (failure: Exception) { ProbeReading.Unavailable(reason(failure)) }
        catch (_: LinkageError) { ProbeReading.Unavailable("библиотека источника недоступна") }

    // Do not publish exception messages: OEM errors can include identifiers or coordinates.
    private fun reason(failure: Throwable): String {
        val cause = if (failure is InvocationTargetException) failure.targetException else failure
        return when (cause) {
            is SecurityException -> "нет разрешения"
            is ClassNotFoundException -> "нет класса Android Car API"
            is NoSuchMethodException, is NoSuchFieldException -> "API источника не поддерживается"
            is IllegalArgumentException -> "свойство не поддерживается"
            else -> "источник недоступен (${cause.javaClass.simpleName})"
        }
    }
}
