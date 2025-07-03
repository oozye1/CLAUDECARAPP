package co.uk.doverguitarteacher.claudecarapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var btnFlag: Button
    private lateinit var mapFragment: SupportMapFragment
    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var carLat: Double? = null
    private var carLng: Double? = null
    private val breadcrumbPoints = mutableListOf<LatLng>()
    private val locationUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var locationUpdateRunnable: Runnable


    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnFlag = findViewById(R.id.btnFlag)
        mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkGooglePlayServices()
        checkPermissions() // Check for permissions before map is ready

        mapFragment.getMapAsync(this)
        btnFlag.setOnClickListener { flagLocation() }
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun flagLocation() {
        if (hasLocationPermission()) {
            try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            carLat = location.latitude
                            carLng = location.longitude
                            val carPosition = LatLng(carLat!!, carLng!!)
                            mMap?.clear()
                            breadcrumbPoints.clear()
                            breadcrumbPoints.add(carPosition)
                            mMap?.addMarker(MarkerOptions().position(carPosition).title("Car Parked Here"))
                            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carPosition, 18f))
                            Toast.makeText(this, "Car location flagged!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Could not get current location.", Toast.LENGTH_LONG).show()
                        }
                    }
            } catch (e: SecurityException) {
                Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates() {
        locationUpdateRunnable = object : Runnable {
            override fun run() {
                if (hasLocationPermission()) {
                    try {
                        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener { location ->
                                if (location != null && carLat != null) {
                                    val currentPosition = LatLng(location.latitude, location.longitude)
                                    if (breadcrumbPoints.lastOrNull() != currentPosition) {
                                        breadcrumbPoints.add(currentPosition)
                                        updateBreadcrumbTrail()
                                    }
                                }
                            }
                    } catch (e: SecurityException) {
                        // Permissions might be revoked while the app is running
                    }
                }
                locationUpdateHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        locationUpdateHandler.post(locationUpdateRunnable)
    }

    private fun stopLocationUpdates() {
        if (::locationUpdateRunnable.isInitialized) {
            locationUpdateHandler.removeCallbacks(locationUpdateRunnable)
        }
    }

    private fun updateBreadcrumbTrail() {
        mMap?.clear()
        if (carLat != null && carLng != null) {
            val carPosition = LatLng(carLat!!, carLng!!)
            mMap?.addMarker(MarkerOptions().position(carPosition).title("Car Parked Here"))
            if (breadcrumbPoints.size > 1) {
                mMap?.addPolyline(
                    PolylineOptions()
                        .addAll(breadcrumbPoints)
                        .color(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                        .width(10f)
                )
            }
        }
    }

    private fun checkGooglePlayServices() {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (status != ConnectionResult.SUCCESS) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, status, 0)?.show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, now we can safely use location features.
                // Refresh the map to show the 'my location' button if the map is already ready.
                mMap?.let {
                    try {
                        it.isMyLocationEnabled = true
                        it.uiSettings.isMyLocationButtonEnabled = true
                    } catch (e: SecurityException) {
                        // This case is unlikely here, but good practice.
                    }
                }
            } else {
                Toast.makeText(this, "Location permission is required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (hasLocationPermission()) {
            try {
                mMap?.isMyLocationEnabled = true
                mMap?.uiSettings?.isMyLocationButtonEnabled = true
            } catch (e: SecurityException) {
                Toast.makeText(this, "Location permission error.", Toast.LENGTH_SHORT).show() // This is the line that was fixed.
            }
        }
        // Set a default view
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.12, 1.31), 12f))
    }
}
