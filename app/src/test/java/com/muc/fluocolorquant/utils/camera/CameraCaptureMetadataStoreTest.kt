package com.muc.fluocolorquant.utils.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CameraCaptureMetadataStoreTest {

    @Test
    fun `sanitizeMetadataToken should replace invalid characters`() {
        val sanitized = CameraCaptureMetadataStore.sanitizeMetadataToken("IMG 2026/03/19:sample?.jpg")

        assertEquals("IMG_2026_03_19_sample_.jpg", sanitized)
    }

    @Test
    fun `buildMetadataFile should append metadata suffix`() {
        val imageFile = File("captures/sample.jpg")

        val metadataFile = CameraCaptureMetadataStore.buildMetadataFile(imageFile)

        assertEquals("sample_camera_metadata.json", metadataFile.name)
    }
}
