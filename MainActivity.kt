package com.example.compassapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.compassapp.ui.theme.CompassAppTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val CustomDarkColor = Color(0xFF042f3d)

/**
 * Główna klasa aktywności aplikacji. Odpowiada za zarządzanie cyklem życia,
 * obsługę sensorów (implementuje SensorEventListener) i wyświetlanie interfejsu użytkownika
 * za pomocą Jetpack Compose. Zablokowana w orientacji pionowej.
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    // Manager systemowych sensorów Androida
    private lateinit var sensorManager: SensorManager
    // Sensor wektora rotacji
    private var rotationVectorSensor: Sensor? = null
    // Sensory zapasowe: Akcelerometr
    private var accelerometer: Sensor? = null
    // Sensory zapasowe: Magnetometr
    private var magnetometer: Sensor? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private val displayAzimuthState = mutableStateOf(0f)
    private val continuousRotationTargetState = mutableStateOf(0f)
    private var smoothedAzimuth: Float = 0f
    private val filterAlpha = 0.15f
    private var isFilterInitialized = false

    private var lastSmoothedAzimuthForDelta: Float? = null
    private val targetUpdateThreshold = 0.5f
    private var lastAzimuthTriggeringUpdate: Float = -1000f
    private var useRotationVector = false

    /**
     * Metoda odpowiedzialna za inicjalizację (sensory, uprawnienia)
     * i ustawienie głównego UI.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        initSensors()
        checkAndRequestPermissions()

        setContent {
            var isDarkMode by rememberSaveable { mutableStateOf(false) }

            CompassAppTheme(darkTheme = isDarkMode) {
                val surfaceBackgroundColor = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.background

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = surfaceBackgroundColor
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {

                        Text(
                            text = "Created by Przemysław Święciński",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkMode) Color.White else CustomDarkColor,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 12.dp, bottom = 4.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val buttonContainerColor: Color
                            val buttonContentColor: Color

                            if (isDarkMode) {
                                buttonContainerColor = Color.White
                                buttonContentColor = CustomDarkColor
                            } else {
                                buttonContainerColor = CustomDarkColor
                                buttonContentColor = Color.White
                            }


                            Button(
                                onClick = { isDarkMode = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonContainerColor,
                                    contentColor = buttonContentColor
                                )
                            ) {
                                Text("Tryb Jasny")
                            }

                            Button(
                                onClick = { isDarkMode = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonContainerColor,
                                    contentColor = buttonContentColor
                                )
                            ) {
                                Text("Tryb Ciemny")
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            val displayAzimuth = displayAzimuthState.value
                            val rotationTarget = continuousRotationTargetState.value

                            val animatedRotation by animateFloatAsState(
                                targetValue = rotationTarget,
                                label = "CompassRotationAnimation",
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                            CompassScreen(
                                azimuthDegrees = displayAzimuth,
                                rotationDegrees = animatedRotation,
                                isDarkMode = isDarkMode
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Metoda ejestrująca nasłuchiwacze sensorów, aby zacząć odbierać dane.
     */
    override fun onResume() {
        super.onResume()
        registerSensors() // Rejestracja sensorów
    }

    /**
     * Metoda wyrejestrowuje nasłuchiwacze sensorów, aby oszczędzać energię.
     */
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    /**
     * Inicjalizuje managera sensorów i próbuje uzyskać dostęp do preferowanego
     * sensora (Rotation Vector) lub sensorów zapasowych (Akcelerometr, Magnetometr)
     */
    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor != null) {
            useRotationVector = true
            Log.i("CompassApp", "Korzystanie z czujnika TYPE_ROTATION_VECTOR.")
        } else {
            useRotationVector = false
            Log.w("CompassApp", "TYPE_ROTATION_VECTOR niedostępny, używanie Akcelerometru + Magnetometru.")
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            if (accelerometer == null || magnetometer == null) {
                Log.e("CompassApp", "Akcelerometr i/lub magnetometr niedostępny.")
                showToast("Wymagane sensory kompasu nie są dostępne!")
            }
        }
    }

    /**
     * Rejestruje nasłuchiwacze dla wybranych sensorów (Rotation Vector lub Akcelerometr+Magnetometr)
     */
    private fun registerSensors() {
        isFilterInitialized = false
        lastSmoothedAzimuthForDelta = null
        lastAzimuthTriggeringUpdate = -1000f
        gravity = null
        geomagnetic = null

        val sensorDelay = SensorManager.SENSOR_DELAY_GAME
        var registrationSuccess = true

        if (useRotationVector) {
            rotationVectorSensor?.let {
                if (!sensorManager.registerListener(this, it, sensorDelay)) {
                    Log.e("CompassApp", "Nie udało się zarejestrować nasłuchiwacza dla czujnika wektora obrotu")
                    registrationSuccess = false
                }
            } ?: run { registrationSuccess = false }
        } else {
            var accelRegistered = false
            var magRegistered = false
            accelerometer?.let {
                accelRegistered = sensorManager.registerListener(this, it, sensorDelay)
                if (!accelRegistered) Log.e("CompassApp", "Nie udało się zarejestrować nasłuchiwacza dla czujnika akcelerometru")
            }
            magnetometer?.let {
                magRegistered = sensorManager.registerListener(this, it, sensorDelay)
                if (!magRegistered) Log.e("CompassApp", "Nie udało się zarejestrować słuchacza dla czujnika pola magnetycznego")
            }
            if (!accelRegistered || !magRegistered) {
                registrationSuccess = false
            }
        }

        if (!registrationSuccess) {
            showToast("Nie udało się zarejestrować sensorów kompasu.")
        }
    }

    /**
     * Metoda informuje użytkownika o niskiej dokładności.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW || accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w("CompassApp", "Dokładność czujnika jest zbyt niska lub niewiarygodna: ${sensor?.name} ($accuracy)")
            showToast("Niska dokładność kompasu. Spróbuj skalibrować (ruch ósemkowy).", Toast.LENGTH_SHORT)
        } else {
            Log.i("CompassApp", "Dokładność czujnika średnia/wysoka: ${sensor?.name} ($accuracy)")
        }
    }

    /**
     * Odczytuje dane, oblicza azymut, filtruje go i aktualizuje stany dla UI.
     */
    override fun onSensorChanged(event: SensorEvent) {
        var currentRawAzimuth: Float? = null

        if (useRotationVector && event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            val rotationVector = event.values
            if (rotationVector.size >= 4) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
            } else {
                Log.w("CompassApp", "Nieprawidłowy rozmiar danych wektora obrotu: ${rotationVector.size}. Ignorowanie zdarzenia.")
                return
            }
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            currentRawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()

        } else if (!useRotationVector) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                gravity = event.values.clone()
            }
            if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                geomagnetic = event.values.clone()
            }

            if (gravity != null && geomagnetic != null) {
                val rotationMatrix = FloatArray(9)
                val inclinationMatrix = FloatArray(9)
                val success = SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)

                if (success) {
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    currentRawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    gravity = null
                    geomagnetic = null
                } else {
                    Log.w("CompassApp", "Nie udało się uzyskać macierzy rotacji za pomocą ACCEL+MAG.")
                }
            }
        }

        currentRawAzimuth?.let { rawAzimuth ->
            var normalizedAzimuth = (rawAzimuth % 360 + 360) % 360

            if (!isFilterInitialized) {
                smoothedAzimuth = normalizedAzimuth
                lastSmoothedAzimuthForDelta = smoothedAzimuth
                displayAzimuthState.value = smoothedAzimuth
                continuousRotationTargetState.value = -smoothedAzimuth
                lastAzimuthTriggeringUpdate = smoothedAzimuth
                isFilterInitialized = true
            } else {
                val filterAngleDiff = shortestAngleDifference(normalizedAzimuth, smoothedAzimuth)
                smoothedAzimuth += filterAlpha * filterAngleDiff
                smoothedAzimuth = (smoothedAzimuth % 360 + 360) % 360
            }

            val diffFromLastUpdateTrigger = abs(shortestAngleDifference(smoothedAzimuth, lastAzimuthTriggeringUpdate))

            if (diffFromLastUpdateTrigger >= targetUpdateThreshold || lastAzimuthTriggeringUpdate == -1000f) {
                lastSmoothedAzimuthForDelta?.let { lastDeltaAzimuth ->
                    val rotationDelta = shortestAngleDifference(smoothedAzimuth, lastDeltaAzimuth)
                    continuousRotationTargetState.value -= rotationDelta
                    displayAzimuthState.value = smoothedAzimuth
                    lastAzimuthTriggeringUpdate = smoothedAzimuth
                    lastSmoothedAzimuthForDelta = smoothedAzimuth

                } ?: run {
                    Log.w("CompassApp", "Brak lastSmoothedAzimuthForDelta podczas aktualizacji, ponowna inicjalizacja")
                    lastSmoothedAzimuthForDelta = smoothedAzimuth
                    continuousRotationTargetState.value = -smoothedAzimuth
                    displayAzimuthState.value = smoothedAzimuth
                    lastAzimuthTriggeringUpdate = smoothedAzimuth
                }
            }
        }
    }

    /**
     * Sprawdza, czy aplikacja posiada uprawnienie ACCESS_FINE_LOCATION.
     * Jeśli nie, wyświetla systemowe okno dialogowe z prośbą o nadanie uprawnienia.
     */
    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Sprawdza wynik dla żądania uprawnień lokalizacji.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Log.i("CompassApp", "Pozwolono na użycie lokalizacji.") // Uprawnienie nadane
            } else {
                Log.w("CompassApp", "Brak pozwolenia do użycia lokalizacji.") // Uprawnienie odrzucone
                showToast("Dokładność kompasu może być ograniczona bez uprawnień lokalizacji.")
            }
        }
    }

    private fun shortestAngleDifference(angle1: Float, angle2: Float): Float {
        val diff = (angle1 - angle2 + 180f) % 360f - 180f
        return if (diff < -180f) diff + 360f else diff
    }


    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        runOnUiThread {
            Toast.makeText(applicationContext, message, duration).show()
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }
}

/**
 * Funkcja kompozycyjna (Composable) odpowiedzialna za rysowanie interfejsu kompasu
 * @param azimuthDegrees Aktualny, wygładzony azymut do wyświetlenia jako tekst (0-360).
 * @param rotationDegrees Aktualny, animowany kąt obrotu obrazka igły kompasu.
 * @param isDarkMode Informacja, czy aktywny jest tryb ciemny (wpływa na kolor tekstu stopni).
 */
@Composable
fun CompassScreen(azimuthDegrees: Float, rotationDegrees: Float, isDarkMode: Boolean) {
    val degreesTextColor = if (isDarkMode) Color.White else CustomDarkColor

    Column(
        modifier = Modifier.wrapContentSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.ic_compass2),
            contentDescription = "Igła kompasu",
            modifier = Modifier
                .size(250.dp)
                .rotate(rotationDegrees)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = String.format("%d°", azimuthDegrees.roundToInt()),
            fontSize = 40.sp,
            style = MaterialTheme.typography.displaySmall,
            color = degreesTextColor
        )
    }
}

@Preview(showBackground = true, name = "Light Mode Portrait", showSystemUi = true)
@Composable
fun DefaultPreviewLightPortrait() {
    CompassAppTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()){
                Text(text = "Kompas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CustomDarkColor, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = CustomDarkColor, contentColor = Color.White)) { Text("Tryb Jasny") }
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = CustomDarkColor, contentColor = Color.White)) { Text("Tryb Ciemny") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center){
                    CompassScreen(azimuthDegrees = 45f, rotationDegrees = -45f, isDarkMode = false)
                }
            }
        }
    }
}


@Preview(showBackground = true, name = "Dark Mode Portrait", showSystemUi = true)
@Composable
fun DefaultPreviewDarkPortrait() {
    CompassAppTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()){
                Text(text = "Kompas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CustomDarkColor)) { Text("Tryb Jasny") }
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CustomDarkColor)) { Text("Tryb Ciemny") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center){
                    CompassScreen(azimuthDegrees = 270f, rotationDegrees = -270f, isDarkMode = true)
                }
            }
        }
    }
}