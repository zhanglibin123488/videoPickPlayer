package com.zlb.video.videopick;

import android.app.Activity;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.backends.pipeline.PipelineDraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.zlb.android.video.R;

import java.util.List;

/**
 * Created by ZhangLIbin on 2016/11/10.
 */

public class VideoAdapter extends BaseAdapter {
    private int HOUR = 60 * 60 * 1000;
    private int MIN = 60 * 1000;
    private ContentResolver contentResolver;
    private Activity activity;
    private List<VideoBean> dataList;
    private BitmapFactory.Options options = new BitmapFactory.Options();

    public VideoAdapter(Activity activity, List<VideoBean> dataList) {
        this.activity = activity;
        this.dataList = dataList;
        contentResolver = activity.getContentResolver();
        options.inDither = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }


    private String formatTime(String str){
        if (isNull(str)){
            return "0";
        }
        long time = Long.parseLong(str);
        int h = (int) (time / HOUR);
        int min = (int) ((time % HOUR)/MIN);
        int sec = (int) ((time % MIN)/1000);
        if (h > 0){
            return String.format("%s:%s:%s", getDoubleString(h), getDoubleString(min), getDoubleString(sec));
        }
        if (min > 0){
            return String.format("%s:%s", getDoubleString(min), getDoubleString(sec));
        }
        return String.format("00:%s", getDoubleString(sec));
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
    private String getDoubleString(int i){
        String string = String.valueOf(i);
        if (isNull(string)){
            return "00";
        }
        if (string.length() == 1){
            return "0"+string;
        }
        return string;
    }

    private String formatSize(String str){
        if (isNull(str)){
            return "0";
        }
        long size = Long.parseLong(str);
        if (size < 1024 * 1024){
            return size/1024 + "KB";
        }
        double v = ((double) size) / (1024 * 1024);
        return doub2String(v)+"MB";
    }
    public String doub2String(Double d)
    {
        return new java.text.DecimalFormat("0.00").format(d);
    }
    @Override
    public int getCount() {
        return dataList == null ? 0 : dataList.size();
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        VideoBean bean = dataList.get(position);
        ViewHolder viewHolder = null;
        if (convertView == null){
            convertView = LayoutInflater.from(activity).inflate(R.layout.list_item_mediapicker, null);
            viewHolder = new ViewHolder();
            viewHolder.duration = (TextView) convertView.findViewById(R.id.duration);
            viewHolder.size = (TextView) convertView.findViewById(R.id.size);
            viewHolder.thumbnail = (SimpleDraweeView) convertView.findViewById(R.id.thumbnail);
            convertView.setTag(viewHolder);
        }else {
            viewHolder = (ViewHolder) convertView.getTag();
        }
        if (isNull(bean.getThumbImage())){
            viewHolder.thumbnail.setImageResource(R.drawable.fallload);
        }else {
            setFrescoImage(viewHolder.thumbnail, bean.getThumbImage(), 200, 200);
        }
        viewHolder.size.setText(formatSize(bean.getSize()));
        viewHolder.duration.setText(formatTime(bean.getDuration()));
        return convertView;
    }

    private class ViewHolder{
        SimpleDraweeView thumbnail;
        TextView size, duration;
    }

    public void setFrescoImage(SimpleDraweeView frescoImage, String url , int w, int h){
        if (isNull(url)){
//            frescoImage.setImageURI(Uri.parse("" + R.drawable.fallload));
            frescoImage.setImageResource(R.drawable.fallload);
            return;
        }
//        int width = ScreenUtil.dip2px(w), height = ScreenUtil.dip2px(h);
        if (!url.startsWith("http")){
            url = "file://"+url;
        }
//        showErrorLog(url);
        Uri uri = Uri.parse(url);
//        frescoImage.setImageURI(uri);
        ImageRequest request = ImageRequestBuilder.newBuilderWithSource(uri)
                .setResizeOptions(new ResizeOptions(w, h))
                .build();
        PipelineDraweeController controller = (PipelineDraweeController) Fresco.newDraweeControllerBuilder()
                .setOldController(frescoImage.getController())
                .setImageRequest(request)
                .build();
        frescoImage.setController(controller);
    }
}
