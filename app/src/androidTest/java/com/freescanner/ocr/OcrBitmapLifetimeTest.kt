package com.freescanner.ocr

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrBitmapLifetimeTest {
    @Test fun cancellationDoesNotRecycleSdkInputOrFinishBeforeItsTask() = runBlocking {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val task = TaskCompletionSource<String>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            recognizeRetainingBitmap(bitmap) { task.task }
        }
        operation.cancel()
        assertFalse("The SDK can still be reading this bitmap", bitmap.isRecycled)
        assertFalse("The source lease must remain active until SDK completion", operation.isCompleted)
        task.setResult("discarded after cancellation")
        operation.join()
        assertTrue(operation.isCancelled)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun synchronousSdkFailureAlsoRecyclesTheOwnedBitmap() = runBlocking {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val failure = runCatching {
            recognizeRetainingBitmap<String>(bitmap) { throw IllegalStateException("synthetic SDK failure") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(bitmap.isRecycled)
    }
}
