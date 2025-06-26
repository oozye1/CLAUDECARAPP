package co.uk.doverguitarteacher.claudecarapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // --- VIEW AND LOGIC COMPONENTS ---
    private lateinit var btnFlag: Button
    private lateinit var btnAR: Button
    private lateinit var previewView: PreviewView
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // --- CameraX Components ---
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    // --- CONSTANTS ---
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    // --- LIFECYCLE METHOD ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize everything
        initViews()
        setupLocationClient()
        setupClickListeners()

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check for permissions when the app starts
        checkAndRequestPermissions()
    }

    // --- SETUP FUNCTIONS ---
    private fun initViews() {
        btnFlag = findViewById(R.id.btnFlag)
        btnAR = findViewById(R.id.btnAR)
        previewView = findViewById(R.id.previewView)
        previewView.visibility = View.GONE // Initially hidden
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

    // --- FEATURE LOGIC (PLACEHOLDERS) ---
    private fun flagParkingSpot() {
        // We will add logic here later
        Toast.makeText(this, "Parking spot flagged!", Toast.LENGTH_SHORT).show()
    }

    private fun toggleARMode() {
        if (previewView.visibility == View.GONE) {
            // Check for camera permission before trying to show/start camera
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                previewView.visibility = View.VISIBLE
                startCamera()
                Toast.makeText(this, "AR mode ON", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Camera permission is required for AR mode.", Toast.LENGTH_LONG).show()
                // Optionally, re-request permission or guide the user
                // You could call checkAndRequestPermissions() again if you want to immediately re-prompt.
            }
        } else {
            previewView.visibility = View.GONE
            stopCamera()
            Toast.makeText(this, "AR mode OFF", Toast.LENGTH_SHORT).show()
        }
    }

    // --- CameraX ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview
                )
                Log.i(TAG, "Camera started successfully.")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        Log.i(TAG, "Camera stopped.")
    }


    // --- PERMISSIONS HANDLING ---
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
            var allRequestedPermissionsGranted = true
            if (grantResults.isEmpty()) {
                // This case should ideally not happen if permissions were requested.
                allRequestedPermissionsGranted = false
                Log.w(TAG, "onRequestPermissionsResult: grantResults array is empty.")
            }

            for (i in permissions.indices) {
                if (i < grantResults.size && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permission granted: ${permissions[i]}")
                } else {
                    Log.w(TAG, "Permission denied: ${permissions[i]}")
                    allRequestedPermissionsGranted = false
                }
            }

            if (allRequestedPermissionsGranted) {
                Toast.makeText(this, "Required permissions granted!", Toast.LENGTH_SHORT).show()
                // If the user had just granted camera permission and the previewView is meant to be visible,
                // you might want to call startCamera() here. However, our current toggleARMode() handles
                // starting the camera on the next click if permissions are now available.
                // For a more immediate effect after permission grant IF the user had already tried to open AR:
                // if (isCameraPermissionGranted() && previewView.visibility == View.VISIBLE) {
                //    startCamera()
                // }
            } else {
                Toast.makeText(this, "Some permissions were denied. App may not function fully.", Toast.LENGTH_LONG).show()
                if (!isCameraPermissionGranted()) {
                    Toast.makeText(this, "Camera permission is essential for AR mode and was denied.", Toast.LENGTH_LONG).show()
                    // You might want to disable the AR button or provide further guidance to the user
                    // on how to grant permissions through app settings.
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopCamera() // Ensure camera is stopped and resources released
    }
}
