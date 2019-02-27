package com.bq.multicamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.AsyncTask
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import java.util.concurrent.Executor

/**
 * Helper type definition that encapsulates 3 sets of output targets:
 *
 *   1. Logical camera
 *   2. First physical camera
 *   3. Second physical camera
 */
typealias DualCameraOutputs = Triple<MutableList<Surface>?, MutableList<Surface>?, MutableList<Surface>?>

/**
 * Main
 */
class MainActivity : AppCompatActivity() {

    lateinit var surface1: Surface
    lateinit var surface2: Surface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val surfaceView1 = findViewById<SurfaceView>(R.id.surface1)
        surface1 = surfaceView1.holder.surface
        val surfaceView2 = findViewById<SurfaceView>(R.id.surface2)
        surface2 = surfaceView2.holder.surface

        val dualCamera = findShortLongCameraPair(cameraManager)
        val outputTargets = DualCameraOutputs(null, mutableListOf(surface1), mutableListOf(surface2))

        // Here we open the logical camera, configure the outputs and create a session
        createDualCameraSession(cameraManager, dualCamera!!, targets = outputTargets) { session ->

            // Create a single request which will have one target for each physical camera.
            // NOTE: Each target will only receive frames from its associated physical camera.
            val requestTemplate = CameraDevice.TEMPLATE_PREVIEW
            val captureRequest = session.device.createCaptureRequest(requestTemplate).apply {
                arrayOf(surface1, surface2).forEach { addTarget(it) }
            }.build()

            session.setRepeatingRequest(captureRequest, null, null)
        }

        findViewById<Button>(R.id.swap_camera_button).setOnClickListener {
            if (surfaceView1.isVisible) {
                surfaceView1.visibility = View.GONE
                surfaceView2.visibility = View.VISIBLE
            } else {
                surfaceView1.visibility = View.VISIBLE
                surfaceView2.visibility = View.GONE
            }
        }
    }

    fun findShortLongCameraPair(manager: CameraManager, facing: Int? = null): DualCamera? {

        return findDualCameras(manager, facing).map {
            val characteristics1 = manager.getCameraCharacteristics(it.physicalId1)
            val characteristics2 = manager.getCameraCharacteristics(it.physicalId2)

            // Query the focal lengths advertised by each physical camera
            val focalLengths1 = characteristics1.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0F)
            val focalLengths2 = characteristics2.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0F)

            // Compute the largest difference between min and max focal lengths between cameras
            val focalLengthsDiff1 = focalLengths2.max()!! - focalLengths1.min()!!
            val focalLengthsDiff2 = focalLengths1.max()!! - focalLengths2.min()!!

            // Return the pair of camera IDs and the difference between min and max focal lengths
            if (focalLengthsDiff1 < focalLengthsDiff2) {
                Pair(DualCamera(it.logicalId, it.physicalId1, it.physicalId2), focalLengthsDiff1)
            } else {
                Pair(DualCamera(it.logicalId, it.physicalId2, it.physicalId1), focalLengthsDiff2)
            }

            // Return only the pair with the largest difference, or null if no pairs are found
        }.sortedBy { it.second }.reversed().lastOrNull()?.first
    }

    fun openDualCamera(cameraManager: CameraManager,
                       dualCamera: DualCamera,
                       executor: Executor = AsyncTask.SERIAL_EXECUTOR,
                       callback: (CameraDevice) -> Unit) {

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Permission it not granted
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 666)
        } else {
            cameraManager.openCamera(
                dualCamera.logicalId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) = callback(device)
                    // Omitting for brevity...
                    override fun onError(device: CameraDevice, error: Int) = onDisconnected(device)
                    override fun onDisconnected(device: CameraDevice) = device.close()
                })
        }
    }

    fun createDualCameraSession(cameraManager: CameraManager,
                                dualCamera: DualCamera,
                                targets: DualCameraOutputs,
                                executor: Executor = AsyncTask.SERIAL_EXECUTOR,
                                callback: (CameraCaptureSession) -> Unit) {

        // Create 3 sets of output configurations: one for the logical camera, and
        // one for each of the physical cameras.
        val outputConfigsLogical = targets.first?.map { OutputConfiguration(it) }

        val outputConfigsPhysical1 = targets.second?.map {
            OutputConfiguration(it).apply { setPhysicalCameraId(dualCamera.physicalId1) } }

        val outputConfigsPhysical2 = targets.third?.map {
            OutputConfiguration(it).apply { setPhysicalCameraId(dualCamera.physicalId2) } }

        // Put all the output configurations into a single flat array
        val outputConfigsAll = arrayOf(
            outputConfigsLogical, outputConfigsPhysical1, outputConfigsPhysical2)
            .filterNotNull().flatMap { it }

        // Instantiate a session configuration that can be used to create a session
        val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
            outputConfigsAll, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = callback(session)
                // Omitting for brevity...
                override fun onConfigureFailed(session: CameraCaptureSession) = session.device.close()
            })

        // Open the logical camera using our previously defined function
        openDualCamera(cameraManager, dualCamera, executor = executor) {

            // Finally create the session and return via callback
            it.createCaptureSession(sessionConfiguration)
        }
    }
}

/**
 * Helper class used to encapsulate a logical camera and two underlying physical cameras.
 */
data class DualCamera(val logicalId: String, val physicalId1: String, val physicalId2: String)

fun findDualCameras(manager: CameraManager, facing: Int? = null): Array<DualCamera> {
    val dualCameras = ArrayList<DualCamera>()

    // Iterate over all the available camera characteristics
    manager.cameraIdList.map {
        Pair(manager.getCameraCharacteristics(it), it)
    }.filter {
        // Filter by cameras facing the requested direction
        facing == null || it.first.get(CameraCharacteristics.LENS_FACING) == facing
    }.filter {
        // Filter by logical cameras
        it.first.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }.forEach {
        // All possible pairs from the list of physical cameras are valid results
        // NOTE: There could be N physical cameras as part of a logical camera grouping
        val physicalCameras = it.first.physicalCameraIds.toTypedArray()
        for (idx1 in 0 until physicalCameras.size) {
            for (idx2 in (idx1 + 1) until physicalCameras.size) {
                dualCameras.add(DualCamera(
                    it.second, physicalCameras[idx1], physicalCameras[idx2]))
            }
        }
    }

    return dualCameras.toTypedArray()
}