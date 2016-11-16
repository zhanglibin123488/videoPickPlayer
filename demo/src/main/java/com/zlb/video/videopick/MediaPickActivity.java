package com.zlb.video.videopick;

import android.app.Activity;
import android.app.LoaderManager;
import android.content.ContentResolver;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;

import com.zlb.android.video.R;
import com.zlb.video.play.MediaPlayActivity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ZhangLIbin on 2016/11/10.
 */

public class MediaPickActivity extends Activity implements LoaderManager.LoaderCallbacks<Cursor> {
    private long MAX_SIZE = 30 * 1024 * 1024;
    private long MAX_DURATION = 60 * 1000;
    private static final String LOADER_EXTRA_URI = "loader_extra_uri";
    private static final String LOADER_EXTRA_PROJECT = "loader_extra_project";
    public static final String[] PROJECT_VIDEO = {MediaStore.MediaColumns._ID, MediaStore.Video.Media.DATA, MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION};
    private GridView mGridview;
    private VideoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mediapicker);
        mGridview = (GridView) findViewById(R.id.gridview);
        initData();
    }

    private void initData() {
        requestMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, PROJECT_VIDEO);
    }
//    Dialog payPopwindos;
    private void requestMedia(Uri uri, String[] projects) {
//        payPopwindos = PayPopwindos.showMsgDialog(mContext, "正在加载本地视频...");
        Bundle bundle = new Bundle();
        bundle.putStringArray(LOADER_EXTRA_PROJECT, projects);
        bundle.putString(LOADER_EXTRA_URI, uri.toString());
        getLoaderManager().initLoader(0, bundle, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle bundle) {
        Uri uri = Uri.parse(bundle.getString(LOADER_EXTRA_URI));
        String[] projects = bundle.getStringArray(LOADER_EXTRA_PROJECT);
        String order = MediaStore.MediaColumns.DATE_ADDED + " DESC";
        return new CursorLoader(this, uri, projects, null, null, order);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if (cursor == null){
            return;
        }
        final List<VideoBean> videoList = new ArrayList<>();
        if (cursor.moveToFirst()){
            do {
                try{
                    VideoBean bean = new VideoBean();
                    bean.setId(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                    bean.setVideoPath(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
                    bean.setTitle(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)));
                    bean.setDuration(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)));
                    bean.setSize(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)));
//                    CommonTools.showErrorLog(bean.getDuration()+"\n"+bean.getId()+"\n"+bean.getVideoPath()+"\n"+bean.getTitle());
//                    if (CommonTools.isNullOrZero(bean.getDuration(), bean.getSize()) || Integer.parseInt(bean.getDuration()) < 10 * 1000){
//                        continue;
//                    }
                    videoList.add(bean);
                }catch (Exception e){
                    e.printStackTrace();
                }
            }while (cursor.moveToNext());
        }
        getVideoThumb(videoList);
    }
    private ContentResolver contentResolver;
    private BitmapFactory.Options options = new BitmapFactory.Options();
    private void getVideoThumb(final List<VideoBean> videoBeanList){
        contentResolver = getContentResolver();
        options.inDither = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        ExecutorService pools = Executors.newFixedThreadPool(5);
        for (final VideoBean videoBean : videoBeanList){
            pools.execute(new Runnable() {
                @Override
                public void run() {
                    String thumbPath = getVideoImage(String.valueOf(videoBean.getId()));//某些破手机需要150多毫秒执行这个方法，比如阿姣的荣耀3c，所以还是放到线程中去执行吧
                    //在适配器里面频繁执行读取文件等IO操作，建议放到线程中执行，以免有些机器会卡主线程
                    if (isNull(thumbPath)){
//                        CommonTools.showErrorLog("haha=="+"本地没有，根据id去取");
                        Bitmap tempBitmap = null;
                        try {
                            tempBitmap = MediaStore.Video.Thumbnails.getThumbnail(contentResolver, videoBean.getId(),  MediaStore.Images.Thumbnails.MINI_KIND, options);
                        } catch (Exception e){
                            e.printStackTrace();
//                            CommonTools.showErrorLog("haha=="+"执行方法1卡死");
                        }
                        if (tempBitmap == null){
//                            CommonTools.showErrorLog("haha=="+"根据ID没取到，用其他");
                            tempBitmap = getVideoThumbnail(videoBean.getVideoPath(), MediaStore.Images.Thumbnails.MINI_KIND);
                        }
                        if (tempBitmap == null){
//                            CommonTools.showErrorLog("haha=="+"还是没取到。。。。这是为什么");
                            return;
                        }
                        if (tempBitmap != null){
//                            CommonTools.showErrorLog("haha=="+"取到，去保存");
                            thumbPath = saveBitmap(tempBitmap, String.valueOf(videoBean.getId()));
//                            if (CommonTools.isNull(thumbPath)){
//                                CommonTools.showErrorLog("haha=="+"没保存成功");
//                            }
                        }
                    }
                    videoBean.setThumbImage(thumbPath);
                    if (adapter != null){
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                adapter.notifyDataSetChanged();
                            }
                        });
                    }
                }
            });
        }
        pools.shutdown();
        adapter = new VideoAdapter(MediaPickActivity.this, videoBeanList);
        mGridview.setAdapter(adapter);
        mGridview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                VideoBean selectVideo = videoBeanList.get(position);
                Intent intent = new Intent(MediaPickActivity.this, MediaPlayActivity.class);
                intent.setData(Uri.parse("file://"+selectVideo.getVideoPath()));
                startActivity(intent);
//                VideoReviewActivity.start(MediaPickActivity.this, selectVideo);
            }
        });
//        if (payPopwindos != null && payPopwindos.isShowing()){
//            payPopwindos.dismiss();
//        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == 20){
            boolean isSupport = data.getBooleanExtra("isSupport", false);
            if (!isSupport){
//                showToast("格式不支持，请重新选择");
                return;
            }
            VideoBean videoBean = (VideoBean) data.getSerializableExtra("videoBean");
            if (Long.parseLong(videoBean.getSize()) > MAX_SIZE){
//                showToast(String.format("选择的视频最大不能超过%sM", MAX_SIZE/(1024 * 1024)));
                return;
            }
            if (Long.parseLong(videoBean.getDuration()) > MAX_DURATION){
//                showToast(String.format("选择的视频最长不能超过%s秒", MAX_DURATION/1000));
                return;
            }
//            Intent intent = new Intent();
//            intent.putExtra("videoBean", videoBean);
//            setResult(20, intent);
//            finish();
        }
    }

    public boolean isNull(String str) {
        if (str == null) {
            return true;
        }
        if (str.replaceAll(" ", "").length() == 0) {
            return true;
        }

        return false;
    }

    public synchronized String saveBitmap(Bitmap bitmap, String name) {
        if (bitmap == null){
            return null;
        }
        String path = fileDir + "/image";
        File pathFile = new File(path);
        if (!pathFile.exists()) {
            if (!pathFile.mkdirs()){
                return null;
            }
        }

        File f = new File(path, name);
        try {
            f.createNewFile();
            FileOutputStream out = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
            out.flush();
            out.close();
            return f.getAbsolutePath();
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public static String getVideoImage(String name){
        String path = fileDir + "/image/"+name;
        if (new File(path).exists()){
            return path;
        }
        return null;
    }

    public Bitmap getVideoThumbnail(String videoPath, int kind) {
        Bitmap bitmap = android.media.ThumbnailUtils.createVideoThumbnail(videoPath, kind);
        return bitmap;
    }

    static String fileDir= Environment.getExternalStorageDirectory().getAbsolutePath()+"/exodemo";;
}
