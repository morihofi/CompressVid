package de.morihofi.compressvid;

import static android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS;
import static com.arthenica.mobileffmpeg.Config.getLastCommandOutput;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.VideoView;

import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.ExecuteCallback;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.arthenica.mobileffmpeg.FFprobe;
import com.arthenica.mobileffmpeg.MediaInformation;
import com.arthenica.mobileffmpeg.Statistics;
import com.arthenica.mobileffmpeg.StatisticsCallback;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

public class MainActivity extends AppCompatActivity {

    VideoView videoView;
    Spinner spVideoProfile;
    SeekBar sbQualityCRF;
    Button btnContinue;
    Spinner spFFmpegPreset;

    MediaInformation orgVideoMediaInfo;

    /**
     * This method returns the seconds in hh:mm:ss time format
     *
     * @param seconds seconds to format
     * @return hh:mm:ss time format
     */
    private String getTime(int seconds) {
        int hr = seconds / 3600;
        int rem = seconds % 3600;
        int mn = rem / 60;
        int sec = rem % 60;
        return String.format("%02d", hr) + ":" + String.format("%02d", mn) + ":" + String.format("%02d", sec);
    }

    /**
     * Format bytes to a human readable format
     *
     * @param bytes bytes to format
     * @return formatted bytes
     */
    @SuppressLint("DefaultLocale")
    public static String humanReadableByteCountSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current());
    }

    String roundOffTo2DecPlaces(float val) {
        return String.format("%.2f", val);
    }

    public enum VIDEO_PROFILE_NAMES {
        H264_REENCODE("H264 ReEncode (Default)", 24, -1, "libx264", "mp4", FFMPEG_PRESETS.medium),
        H264_240p("H264 240p", 35, 240, "libx264", "mp4", FFMPEG_PRESETS.medium),
        H264_360p("H264 360p", 30, 360, "libx264", "mp4", FFMPEG_PRESETS.medium),
        H264_480p("H264 480p", 24, 480, "libx264", "mp4", FFMPEG_PRESETS.medium),
        H264_720p("H264 720p", 24, 720, "libx264", "mp4", FFMPEG_PRESETS.medium),
        H264_1080p("H264 1080p", 24, 1080, "libx264", "mp4", FFMPEG_PRESETS.medium);

        private String name;
        private String codec;
        private String ext;
        private int quality;
        private int width;
        private FFMPEG_PRESETS preset;


        VIDEO_PROFILE_NAMES(String name, int quality, int width, String codec, String ext, FFMPEG_PRESETS preset) {
            this.name = name;
            this.quality = quality;
            this.width = width;
            this.codec = codec;
            this.ext = ext;
            this.preset = preset;
        }

        public String getName() {
            return name;
        }

        public int getQuality() {
            return quality;
        }

        public int getWidth() {
            return width;
        }

        public String getCodec() {
            return codec;
        }

        //****** Reverse Lookup ************//

        public static Optional<VIDEO_PROFILE_NAMES> get(String name) {
            return Arrays.stream(VIDEO_PROFILE_NAMES.values())
                    .filter(profile -> profile.name.equals(name))
                    .findFirst();
        }


        public String getExtension() {
            return ext;
        }

        public FFMPEG_PRESETS getFFmpegPreset() {
            return preset;
        }
    }


    public static String[] getValuesOfVideoProfileEnum() {

        ArrayList<String> ret = new ArrayList<>();

        for (VIDEO_PROFILE_NAMES profile : VIDEO_PROFILE_NAMES.values()) {
            ret.add(profile.getName());
        }

        return ret.toArray(new String[0]);
    }


    public static String[] getValuesOfFFmpegPresetsEnum() {

        ArrayList<String> ret = new ArrayList<>();

        for (FFMPEG_PRESETS preset : FFMPEG_PRESETS.values()) {
            ret.add(preset.getName());
        }

        return ret.toArray(new String[0]);
    }

    public enum FFMPEG_PRESETS {
        ultrafast("ultrafast"),
        superfast("superfast"),
        veryfast("veryfast"),
        faster("faster"),
        fast("fast"),
        medium("medium"),
        slow("slow"),
        slower("slower"),
        veryslow("veryslow");


        private String preset;

        FFMPEG_PRESETS(String preset) {
            this.preset = preset;
        }

        //****** Reverse Lookup ************//

        public static Optional<FFMPEG_PRESETS> get(String name) {
            return Arrays.stream(FFMPEG_PRESETS.values())
                    .filter(preset -> preset.preset.equals(name))
                    .findFirst();
        }


        public String getName() {
            return preset;
        }
    }


    private int video_width = -1;
    private String video_codec = "";
    private String video_ext = "";
    private String video_ffmpeg_preset = "";

    private static final String[] profileNames = getValuesOfVideoProfileEnum();
    private static final String[] ffmpegPresetsNames = getValuesOfFFmpegPresetsEnum();


    public void loadPresetProfile(VIDEO_PROFILE_NAMES profile) {
        if (VIDEO_PROFILE_NAMES.get(profile.name).isPresent()) {
            VIDEO_PROFILE_NAMES profileObj = VIDEO_PROFILE_NAMES.get(profile.name).get();


            video_width = profileObj.getWidth();
            video_codec = profileObj.getCodec();
            video_ext = profileObj.getExtension();
            video_ffmpeg_preset = profileObj.getFFmpegPreset().getName();

            sbQualityCRF.setProgress(profileObj.getQuality());
            int i = 0;
            for (String s : ffmpegPresetsNames) {
                if (s.equals(video_ffmpeg_preset)) {
                    spFFmpegPreset.setSelection(i);
                    break;
                }
                i++;
            }


        }
        ;

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_activity_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {

        switch (item.getItemId()) {
            case R.id.men_showmediainfo:
                AlertDialog alert = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.msg_mediainfo_title))
                        .setMessage(
                                "Format: " + orgVideoMediaInfo.getFormat() + "\n" +
                                        "Duration: " + getTime((int) Double.parseDouble(orgVideoMediaInfo.getDuration())) + "\n" +
                                        "Bitrate: " + FileUtils.byteCountToDisplaySize(Long.parseLong(orgVideoMediaInfo.getBitrate())) + "ps\n" +
                                        "Stream count: " + orgVideoMediaInfo.getStreams().size()
                        )
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            dialog.dismiss();
                        })
                        .show();



                break;
            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.videoView);
        spVideoProfile = findViewById(R.id.spVideoProfile);
        sbQualityCRF = findViewById(R.id.sbQualityCRF);
        btnContinue = findViewById(R.id.btnContinue);
        spFFmpegPreset = findViewById(R.id.spFFmpegPreset);

        //Clean App cache
        FileUtils.deleteQuietly(getApplicationContext().getCacheDir());

        //Ask for read permission
        int permissionResult = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE);
        if (permissionResult == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                    }, 1);
        }


        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profileNames);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVideoProfile.setAdapter(profileAdapter);
        profileAdapter.notifyDataSetChanged();
        loadPresetProfile(VIDEO_PROFILE_NAMES.H264_REENCODE); //Load default preset

        ArrayAdapter<String> ffmpegPresetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ffmpegPresetsNames);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFFmpegPreset.setAdapter(ffmpegPresetAdapter);

        spVideoProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                if (VIDEO_PROFILE_NAMES.get(profileNames[position]).isPresent()) {
                    loadPresetProfile(VIDEO_PROFILE_NAMES.get(profileNames[position]).get());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                // your code here
            }

        });

        spFFmpegPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (FFMPEG_PRESETS.get(ffmpegPresetsNames[position]).isPresent()) {
                    video_ffmpeg_preset = ffmpegPresetsNames[position];
                }

            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });








        /*
         *************************************
         * REGISTER COMPONENTS COMPLETE
         *************************************
         */

        Intent receivedIntent = getIntent();
        String receivedAction = receivedIntent.getAction();
        String receivedType = receivedIntent.getType();


        if (receivedAction.equals(Intent.ACTION_SEND) && receivedType.startsWith("video/")) {
            Uri videoUri = (Uri) receivedIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            //Video öffnen

            final MediaController mc = new MediaController(this);
            mc.setEnabled(true);
            mc.setAnchorView(videoView);

            videoView.setVideoURI(videoUri);
            videoView.setMediaController(mc);
            //videoView.start();


            try {


                String fileExt = MimeTypeMap.getFileExtensionFromUrl(videoUri.toString());
                File file = new File(getCacheDir(), "cachedVideo." + fileExt);
                try (InputStream input = getContentResolver().openInputStream(videoUri)) {

                    try (OutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4 * 1024]; // or other buffer size
                        int read;

                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }

                        output.flush();
                    }
                }
                file.deleteOnExit();

                orgVideoMediaInfo = FFprobe.getMediaInformation(file.getPath());

                //Register continue button
                btnContinue.setOnClickListener((l) -> {

                    File outputDir = MainActivity.this.getCacheDir(); // context being the Activity pointer
                    File fileEncFile = null;
                    try {
                        fileEncFile = File.createTempFile("temp-encode", "." + video_ext, outputDir);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }


                    String exe = "-y -i " + file.getPath() + " -c:v " + video_codec;

                    if (video_width != -1) {
                        exe += " -vf scale=" + video_width + ":-2";
                    }

                    exe += " -crf " + sbQualityCRF.getProgress() + " -preset " + video_ffmpeg_preset + " " + fileEncFile.getPath();

                    Log.i("CONVERT", "Executing FFMPEG with " + exe);

                    ProgressDialog progressDialog = new ProgressDialog(MainActivity.this);
                    progressDialog.setTitle(R.string.title_processing);
                    progressDialog.setMessage(getString(R.string.status_encoding));
                    progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                    progressDialog.setProgress(0);
                    progressDialog.setCancelable(true);
                    progressDialog.setMax(100);
                    progressDialog.show();
                    videoView.pause();

                    File finalFileEncFile = fileEncFile;

                    Config.enableStatisticsCallback(new StatisticsCallback() {
                        public void apply(Statistics newStatistics) {

                            Log.d(Config.TAG, String.format("frame: %d, time: %d", newStatistics.getVideoFrameNumber(), newStatistics.getTime()));

                            if (progressDialog.isShowing()) {
                                runOnUiThread(() -> {
                                    progressDialog.setMessage(String.format(getString(R.string.status_encoding_info),
                                            String.valueOf(newStatistics.getVideoFrameNumber()),
                                            roundOffTo2DecPlaces(newStatistics.getVideoFps()),
                                            getTime(newStatistics.getTime() / 1000),
                                            roundOffTo2DecPlaces((float) newStatistics.getSpeed())

                                    ));
                                    try {
                                        double progress = (newStatistics.getTime() / 1000) / Double.parseDouble(orgVideoMediaInfo.getDuration());

                                        progressDialog.setProgress((int) (progress * 100));
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                });

                            }

                        }
                    });

                    long executionId = FFmpeg.executeAsync(exe, new ExecuteCallback() {
                        @Override
                        public void apply(final long executionId, final int returnCode) {
                            progressDialog.dismiss();

                            if (returnCode == RETURN_CODE_SUCCESS) {


                                AlertDialog alert = new AlertDialog.Builder(MainActivity.this)
                                        .setTitle(R.string.msg_encoding_finished_title)
                                        .setMessage(String.format(getString(R.string.msg_encoding_finished_text), humanReadableByteCountSI(finalFileEncFile.length())))
                                        .setCancelable(false)
                                        .setPositiveButton(R.string.action_share, (dialog, id) -> {


                                            Intent i = new Intent(Intent.ACTION_SEND, FileProvider.getUriForFile(MainActivity.this, BuildConfig.APPLICATION_ID + ".provider", finalFileEncFile));
                                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                            //startActivity(i);
                                            startActivity(Intent.createChooser(i, getString(R.string.msg_sharevideowith)));

                                            finish();
                                            //Toast.makeText(getApplicationContext(), "Share",
                                            //         Toast.LENGTH_SHORT).show();
                                        })
                                        /*
                                        .setNeutralButton(R.string.action_close, (dialog,id) -> {
                                            finish();
                                        })
                                        */
                                        .setNegativeButton(R.string.action_tryagain, (dialog, id) -> {
                                            //  Action for 'NO' Button
                                            dialog.cancel();

                                        })

                                        .create();
                                alert.show();


                            } else if (returnCode == RETURN_CODE_CANCEL) {
                                Log.i(Config.TAG, "Async command execution cancelled by user.");
                                Toast.makeText(MainActivity.this, R.string.toast_encoding_canceledbyuser, Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(MainActivity.this, "Encoding failed with code " + returnCode, Toast.LENGTH_LONG).show();
                                Log.i(Config.TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                            }
                        }

                    });

                    progressDialog.setOnCancelListener((e) -> {
                        FFmpeg.cancel(executionId);
                    });


                });


                //info.
                // tvVideoLength.setText(info.getDuration());
            } catch (IOException e) {
                e.printStackTrace();
                Log.e("OPENFILE", "IO Error", e);
                Toast.makeText(MainActivity.this, "IO Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }

        } else {
            finish();
        }


    }
}