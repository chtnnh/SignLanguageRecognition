        
        try {
            retriever.setDataSource(context, videoUri)
            
            // Get video duration and frame rate
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationString?.toLongOrNull() ?: 0L
            
            if (duration > 0) {
                // Calculate time intervals to extract exactly 30 frames
                val timeInterval = duration / TARGET_FRAME_COUNT
                
                for (i in 0 until TARGET_FRAME_COUNT) {
                    val timeUs = i * timeInterval * 1000 // Convert to microseconds
                    try {
                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        bitmap?.let { frames.add(it) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // If we can't get a frame, try to duplicate the last successful frame
                        if (frames.isNotEmpty()) {
                            frames.add(frames.last())
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        frames
    }
    
    suspend fun processVideoFile(videoUri: Uri, classifier: SignLanguageClassifier): String = withContext(Dispatchers.IO) {
        try {
            val frames = extractFramesFromVideo(videoUri)
            
            if (frames.isEmpty()) {
                return@withContext "Failed to extract frames from video"
            }
            
            if (frames.size < TARGET_FRAME_COUNT) {
                return@withContext "Only extracted ${frames.size} frames, need $TARGET_FRAME_COUNT"
            }
            
            // Resize frames to 300x1662 and classify
            val result = classifier.classifyVideoSequence(frames)
            
            // Clean up bitmaps
            frames.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            
            result
            
        } catch (e: Exception) {
            e.printStackTrace()
            "Error processing video: ${e.message}"
        }
    }
}
