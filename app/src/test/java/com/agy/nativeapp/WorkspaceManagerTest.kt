package com.agy.nativeapp

import com.agy.nativeapp.engine.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceManagerTest {

    @Test
    fun resolvesExternalStoragePrimaryAndSdCardDocIds() {
        val primary = WorkspaceManager.resolveExternalStorageDocId(
            "primary:AGYProjects/demo",
            primaryRootOverride = "/storage/emulated/0"
        )
        assertEquals("/storage/emulated/0/AGYProjects/demo", primary)

        val home = WorkspaceManager.resolveExternalStorageDocId(
            "home:Site",
            primaryRootOverride = "/storage/emulated/0"
        )
        assertEquals("/storage/emulated/0/Documents/Site", home)

        val sdCard = WorkspaceManager.resolveExternalStorageDocId("ABCD-1234:Work/Site")
        assertEquals("/storage/ABCD-1234/Work/Site", sdCard)

        assertNull(WorkspaceManager.resolveExternalStorageDocId("invalid_without_colon"))
    }
}
