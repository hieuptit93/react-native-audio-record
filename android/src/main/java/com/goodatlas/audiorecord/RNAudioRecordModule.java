package com.goodatlas.audiorecord;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.util.Base64;
import android.util.Log;
import android.media.AudioManager;
import android.content.Context;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class RNAudioRecordModule extends ReactContextBaseJavaModule {

    private final String TAG = "RNAudioRecord";
    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;
    private AudioManager audioManager;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private int bufferSize;
    private boolean isRecording;
    private boolean isInitialized;

    private String tmpFile;
    private String outFile;
    private Promise stopRecordingPromise;
    private Thread recordingThread;

    public RNAudioRecordModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);
        this.isInitialized = false;
    }

    @Override
    public String getName() {
        return "RNAudioRecord";
    }

    @ReactMethod
    public void init(ReadableMap options) {
        try {
            cleanup();  // Cleanup any existing resources

            sampleRateInHz = 44100;
            if (options.hasKey("sampleRate")) {
                sampleRateInHz = options.getInt("sampleRate");
            }

            channelConfig = AudioFormat.CHANNEL_IN_MONO;
            if (options.hasKey("channels")) {
                if (options.getInt("channels") == 2) {
                    channelConfig = AudioFormat.CHANNEL_IN_STEREO;
                }
            }

            audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            if (options.hasKey("bitsPerSample")) {
                if (options.getInt("bitsPerSample") == 8) {
                    audioFormat = AudioFormat.ENCODING_PCM_8BIT;
                }
            }

            audioSource = AudioSource.VOICE_RECOGNITION;
            if (options.hasKey("audioSource")) {
                audioSource = options.getInt("audioSource");
            }

            String documentDirectoryPath = getReactApplicationContext().getFilesDir().getAbsolutePath();
            outFile = documentDirectoryPath + "/" + "audio.wav";
            tmpFile = documentDirectoryPath + "/" + "temp.pcm";
            if (options.hasKey("wavFile")) {
                String fileName = options.getString("wavFile");
                outFile = documentDirectoryPath + "/" + fileName;
            }

            isRecording = false;
            eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);

            bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                throw new Exception("Invalid buffer size");
            }

            int recordingBufferSize = bufferSize * 3;
            recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);
            
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new Exception("AudioRecord initialization failed");
            }

            isInitialized = true;
            Log.d(TAG, "Audio recorder initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error initializing audio recorder: " + e.getMessage());
            cleanup();
            throw new RuntimeException("Failed to initialize audio recorder", e);
        }
    }

    @ReactMethod
    public void start() {
        if (!isInitialized || recorder == null) {
            Log.e(TAG, "Audio recorder not initialized");
            return;
        }

        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }

        try {
            // Request audio focus
            int result = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw new Exception("Could not get audio focus");
            }

            isRecording = true;
            recorder.startRecording();
            Log.d(TAG, "Started recording");

            recordingThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        writeAudioDataToFile();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in recording thread: " + e.getMessage());
                    }
                }
            });
            recordingThread.start();

        } catch (Exception e) {
            Log.e(TAG, "Error starting recording: " + e.getMessage());
            isRecording = false;
            cleanup();
        }
    }

    private void writeAudioDataToFile() {
        byte[] buffer = new byte[bufferSize];
        FileOutputStream os = null;

        try {
            os = new FileOutputStream(tmpFile);
            int count = 0;

            while (isRecording) {
                int bytesRead = recorder.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && ++count > 2) {  // Skip first 2 buffers
                    String base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP);
                    eventEmitter.emit("data", base64Data);
                    os.write(buffer, 0, bytesRead);
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Error writing audio data: " + e.getMessage());
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing output stream: " + e.getMessage());
            }
        }

        if (stopRecordingPromise != null) {
            try {
                saveAsWav();
                stopRecordingPromise.resolve(outFile);
            } catch (Exception e) {
                stopRecordingPromise.reject("SAVE_ERROR", e.getMessage());
            }
        }
    }

    @ReactMethod
    public void stop(Promise promise) {
        if (!isRecording) {
            promise.resolve(null);
            return;
        }

        stopRecordingPromise = promise;
        isRecording = false;

        try {
            if (recorder != null) {
                recorder.stop();
            }
            audioManager.abandonAudioFocus(null);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording: " + e.getMessage());
        }
    }

    public void cleanup() {
        try {
            if (recorder != null) {
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.stop();
                }
                recorder.release();
                recorder = null;
            }

            if (recordingThread != null) {
                recordingThread.interrupt();
                recordingThread = null;
            }

            isRecording = false;
            isInitialized = false;
            audioManager.abandonAudioFocus(null);

            // Delete temporary files
            deleteTempFile();
            deleteWavFile();

        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup: " + e.getMessage());
        }
    }

    private void saveAsWav() throws Exception {
        FileInputStream in = null;
        FileOutputStream out = null;

        try {
            in = new FileInputStream(tmpFile);
            out = new FileOutputStream(outFile);
            long totalAudioLen = in.getChannel().size();
            long totalDataLen = totalAudioLen + 36;

            addWavHeader(out, totalAudioLen, totalDataLen);

            byte[] data = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = in.read(data)) != -1) {
                out.write(data, 0, bytesRead);
            }

            Log.d(TAG, "WAV file saved: " + outFile);

        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
            deleteTempFile();
        }
    }

    private void addWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen) throws IOException {
        long sampleRate = sampleRateInHz;
        int channels = channelConfig == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        int bitsPerSample = audioFormat == AudioFormat.ENCODING_PCM_8BIT ? 8 : 16;
        long byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R';  // RIFF chunk
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';  // WAVE chunk
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // fmt chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of fmt chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1 for PCM
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign;
        header[33] = 0;
        header[34] = (byte) bitsPerSample;
        header[35] = 0;
        header[36] = 'd';  // data chunk
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    private void deleteTempFile() {
        File file = new File(tmpFile);
        file.delete();
    }

    private void deleteWavFile() {
        File file = new File(outFile);
        file.delete();
    }

    @Override
    public void onCatalystInstanceDestroy() {
        cleanup();
        super.onCatalystInstanceDestroy();
    }
}