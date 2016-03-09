package com.musicplus.app.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.musicplus.R;
import com.musicplus.app.MainApplication;
import com.musicplus.entry.AudioEntry;
import com.musicplus.media.AudioDecoder;
import com.musicplus.media.MultiRawAudioPlayer;
import com.musicplus.media.MultiRawAudioPlayer.OnRawAudioPlayListener;
import com.musicplus.media.VideoMuxer;
import com.musicplus.media.VideoRecorder;
import com.musicplus.media.VideoRecorder.OnVideoRecordListener;
import com.musicplus.utils.MD5Util;

/**
 * 创作页面
 * 
 * @author Darcy
 */
public class ComposeActivity extends BaseActivity implements View.OnClickListener, OnRawAudioPlayListener{
	
	private TextureView svVideoPreview;
	private Button btnRecord;
	private Button btnAddMusic;
	private Button btnPreview;
	private Button btnRedoRecord;
	private LinearLayout containerAudioTracks;
	private VideoRecorder videoRecorder;
	private Set<AudioEntry> addAudioTracks = new HashSet<AudioEntry>();
	private MultiRawAudioPlayer mBackMisicPlayer;
	private String mTempMixAudioFile;
	private  CyclicBarrier recordBarrier = new CyclicBarrier(2);
	
	private int recordState;
	private final static String TEMP_RECORD_VIDEO_FILE = MainApplication.TEMP_VIDEO_PATH + "/temp_record_video";
	
	private final static int RECORD_STATE_INITIAL = 0x0;
	private final static int RECORD_STATE_PREPARING = 0x1;
	private final static int RECORD_STATE_RECORDING = 0x2;
	private final static int RECORD_STATE_DONE = 0x3;
	
	private final static int REQUEST_CODE_ADD_MUSIC = 0x1;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_compose);
		svVideoPreview = findView(R.id.sv_video_preview);
		btnRecord = findView(R.id.btn_record);
		btnRecord.setOnClickListener(this);
		btnAddMusic = findView(R.id.btn_add_music);
		btnAddMusic.setOnClickListener(this); 
		containerAudioTracks = findView(R.id.container_musics);
		btnPreview = findView(R.id.btn_preview);
		btnPreview.setOnClickListener(this);
		btnRedoRecord = findView(R.id.btn_re_record);
		btnRedoRecord.setOnClickListener(this);
		
		videoRecorder = new VideoRecorder(this, svVideoPreview, TEMP_RECORD_VIDEO_FILE,recordBarrier);
		recordState = RECORD_STATE_INITIAL;
		
		LayoutParams lpPre = svVideoPreview.getLayoutParams();
		lpPre.height = 1080 * 640 / 480;
		svVideoPreview.setLayoutParams(lpPre);
	}

	@Override
	public void onClick(View v) {
		switch(v.getId()){
		case R.id.btn_record:
			performRecord();
			break;
		case R.id.btn_add_music:
			performAddMusic();
			break;
		case R.id.btn_preview:
			new MixVideoAndAudioTask().execute();
			break;
		case R.id.btn_re_record:
			performRedoRecord();
			break;
		}
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		videoRecorder.release();
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		svVideoPreview.postDelayed(new Runnable(){
			@Override
			public void run() {
				videoRecorder.startPreview();
			}
		}, 100);
	}
	
	@Override
	protected void onStop() {
		super.onStop();
		stopBackgroundMusic();
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode == REQUEST_CODE_ADD_MUSIC && resultCode == RESULT_OK){
			AudioEntry audioEntry = (AudioEntry) data.getSerializableExtra("audio");
			new DecodeAudioTask(audioEntry).execute();
		}
	}
	
	private void performRecord(){
		if(recordState == RECORD_STATE_INITIAL){
			recordState = RECORD_STATE_PREPARING;
			videoRecorder.setOnVideoRecordListener(new OnVideoRecordListener() {
				@Override
				public void onStarted() {
					recordState = RECORD_STATE_RECORDING;
					btnRecord.setText(R.string.complete_record);
				}
				
				public void onError(int errorCode) {
				}
			});
			videoRecorder.startRecord();
			playBackgroundMusic();
		}else if(recordState == RECORD_STATE_RECORDING){
			recordState = RECORD_STATE_DONE;
			videoRecorder.release();
			recordBarrier.reset();
			stopBackgroundMusic();
			btnRecord.postDelayed(new Runnable() {
				@Override
				public void run() {
					new MixVideoAndAudioTask().execute();
				}
			}, 5000);
		}
	}
	
	@Override
	public void onPlayStart() {
		videoRecorder.startRecord();
	}

	@Override
	public void onPlayStop(String tempMixFile) {
		mTempMixAudioFile = tempMixFile;
	}

	@Override
	public void onPlayComplete(String tempMixFile) {
		mTempMixAudioFile = tempMixFile;
	}
	
	private void performAddMusic(){
		Intent addMusicIntent = new Intent(this,AudioChooserActivity.class);
		startActivityForResult(addMusicIntent, REQUEST_CODE_ADD_MUSIC);
	}
	
	private void performRedoRecord(){
		recordState = RECORD_STATE_INITIAL;
		btnRecord.setText(R.string.record);
		videoRecorder.startPreview();
	}
	
	private void playBackgroundMusic(){
		int trackSize = addAudioTracks.size();
		if(trackSize > 0){
			mBackMisicPlayer = new MultiRawAudioPlayer(addAudioTracks.toArray(new AudioEntry[trackSize]),recordBarrier);
			mBackMisicPlayer.setOnRawAudioPlayListener(this);
			mBackMisicPlayer.play();
		}
	}
	
	private void stopBackgroundMusic(){
		if(mBackMisicPlayer!=null)
			mBackMisicPlayer.stop();
	}
	
	class MixVideoAndAudioTask extends AsyncTask<Void, Long, Boolean>{

		@Override
		protected Boolean doInBackground(Void... params) {
			String videoFile = MainApplication.RECORD_VIDEO_PATH + "/test.mp4";
			VideoMuxer videoMuxer = VideoMuxer.createVideoMuxer(videoFile);
			videoMuxer.mixRawAudio(new File(TEMP_RECORD_VIDEO_FILE), new File(mTempMixAudioFile));
			return true;
		}
		
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);
			Toast.makeText(ComposeActivity.this, "生成视频完成", 3000).show();
		}
	}
	
	class DecodeAudioTask extends AsyncTask<Void, Long, Boolean>{

		AudioEntry decAudio;
		
		DecodeAudioTask(AudioEntry decAudio){
			this.decAudio = decAudio;
		}
		
		@Override
		protected Boolean doInBackground(Void... params) {
			String decodeFilePath = MainApplication.TEMP_AUDIO_PATH + "/" + MD5Util.getMD5Str(decAudio.fileUrl);
			File decodeFile = new File(decodeFilePath);
			if(decodeFile.exists()){
				decAudio.fileUrl = decodeFilePath;
				return true;
			}
			
			if(decAudio.mime.contains("x-ms-wma")){
				FileInputStream fisWavFile = null;
				FileOutputStream fosRawAudioFile = null;
				try {
					 fisWavFile = new FileInputStream(decAudio.fileUrl);
					 fosRawAudioFile = new FileOutputStream(decodeFile);
					 fisWavFile.read(new byte[44]);
					 byte[] rawBuf = new byte[1024];
					 int readCount;
					 while((readCount = fisWavFile.read(rawBuf)) != -1){
						 fosRawAudioFile.write(rawBuf, 0, readCount);
					 }
					 return true;
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}finally{
					try {
					if(fisWavFile != null)
						fisWavFile.close();
						
					if(fosRawAudioFile != null)
						fosRawAudioFile.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				return false;
			}else{
				AudioDecoder audioDec = AudioDecoder.createDefualtDecoder(decAudio.fileUrl);
				try {
					decAudio.fileUrl = decodeFilePath;
					audioDec.decodeToFile(decodeFilePath);
					return true;
				} catch (IOException e) {
					e.printStackTrace();
					return false;
				}
			}
		}
		
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);
			if(result){
				addMisicTrack(decAudio);
			}
		}
	}
	
	private void addMisicTrack(AudioEntry decAudio){
		if(addAudioTracks.contains(decAudio))
			return;
		
		addAudioTracks.add(decAudio);
		View viewTrack = View.inflate(this, R.layout.listitem_audio_info, null);
		TextView tvName = (TextView)viewTrack.findViewById(R.id.tv_file_name);
		TextView tvArtist = (TextView)viewTrack.findViewById(R.id.tv_artist);
		tvName.setText(decAudio.fileName);
		tvArtist.setText(decAudio.artist);
		
		containerAudioTracks.addView(viewTrack);
	}
}