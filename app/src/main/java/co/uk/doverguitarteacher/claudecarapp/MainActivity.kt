package co.uk.doverguitarteacher.claudecarapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var btnFlag: Button
    private lateinit var btnAR: Button
    private lateinit var previewView: PreviewView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var mMap: GoogleMap? = null
    private lateinit var mapFragmentView: View
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupLocationClient()
        setupClickListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        checkAndRequestPermissions()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        Log.i(TAG, "Map is ready.")
        mapFragmentView = supportFragmentManager.findFragmentById(R.id.map_fragment)!!.requireView()

        val dover = LatLng(51.1296, 1.3111)
        mMap?.addMarker(MarkerOptions().position(dover).title("Marker in Dover"))
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(dover, 12f))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
        }
    }

    private fun initViews() {
        btnFlag = findViewById(R.id.btnFlag)
        btnAR = findViewById(R.id.btnAR)
        previewView = findViewById(R.id.previewView)
    }

    private fun setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun setupClickListeners() {
        btnFlag.setOnClickListener {
            flagParkingSpot()
        }
        btnAR.setOnClickListener {
            toggleARMode()
        }
    }

    @SuppressLint("MissingPermission")
    private fun flagParkingSpot() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 500
            priority = Priority.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    val lat = location.latitude
                    val lng = location.longitude
                    val carLatLng = LatLng(lat, lng)
                    mMap?.addMarker(MarkerOptions().position(carLatLng).title("Parked Car"))
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carLatLng, 17f))
                } else {
                    Toast.makeText(applicationContext, "Unable to get current location", Toast.LENGTH_SHORT).show()
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun toggleARMode() {
        if (!::mapFragmentView.isInitialized) {
            Toast.makeText(this, "Map is not ready yet, please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        if (previewView.visibility == View.GONE) {
            if (isCameraPermissionGranted()) {
                mapFragmentView.visibility = View.GONE
                previewView.visibility = View.VISIBLE
                startCamera()
                Toast.makeText(this, "AR mode ON", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Camera permission is required for AR mode.", Toast.LENGTH_LONG).show()
                checkAndRequestPermissions()
            }
        } else {
            previewView.visibility = View.GONE
            mapFragmentView.visibility = View.VISIBLE
            stopCamera()
            Toast.makeText(this, "AR mode OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview)
                Log.i(TAG, "Camera started successfully.")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        Log.i(TAG, "Camera stopped.")
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (!isCameraPermissionGranted()) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Log.i(TAG, "All initially checked permissions already granted.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
                if (mMap != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    mMap?.isMyLocationEnabled = true
                }
            } else {
                Toast.makeText(this, "Permissions denied. Some features may not work.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopCamera()
    }
}
