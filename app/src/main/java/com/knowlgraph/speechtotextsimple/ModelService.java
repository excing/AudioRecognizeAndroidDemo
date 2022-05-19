package com.knowlgraph.speechtotextsimple;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.jetbrains.annotations.NotNull;
import org.vosk.Model;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModelService {

    public static void unpack(Context ctx, Uri modelZipUri, Callback<Model> modelCallback, Callback<String> messageCallback) {
        Executor executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            handler.post(() -> messageCallback.onComplete("开始模型预加载..."));
            List<String> segments = modelZipUri.getPathSegments();
            String[] pathSegments = segments.get(segments.size() - 1).split("/");
            File modelFolder = new File(ctx.getFilesDir(), pathSegments[pathSegments.length - 1]);

            try {
                String modelPath = getModelPath(modelFolder);
                Model model = new Model(modelPath);
                handler.post(() -> {
                    modelCallback.onComplete(model);
                    messageCallback.onComplete("模型加载成功");
                });
                return;
            } catch (IOException | NullPointerException e) {
                e.printStackTrace();
            }

            try (InputStream is = ctx.getContentResolver().openInputStream(modelZipUri)) {
                String dist = unzipFolder(is, modelFolder, s -> handler.post(() -> messageCallback.onComplete(s)));
                handler.post(() -> messageCallback.onComplete("开始加载模型……"));
                Model model = new Model(dist);
                handler.post(() -> {
                    modelCallback.onComplete(model);
                    messageCallback.onComplete("模型加载成功");
                });
            } catch (IOException e) {
                modelFolder.deleteOnExit();
                handler.post(() -> messageCallback.onComplete(e.getLocalizedMessage()));
            }
        });
    }

    @NotNull
    private static String getModelPath(File modelFile) throws IOException {
        if (!modelFile.exists()) {
            throw new NullPointerException("未找到模型文件");
        }
        File[] files = modelFile.listFiles();
        if (files == null || files.length == 0) {
            throw new IOException("模型文件格式异常，解压失败");
        }
        if (files.length == 1 && files[0].isDirectory()) {
            // 目录下有且仅有一个文件夹，表示此压缩文件为目录压缩
            return files[0].getAbsolutePath();
        }
        return modelFile.getAbsolutePath();
    }

    public static String unzipFolder(InputStream in, File modelDistFolder, Callback<String> call) throws IOException {
        try (ZipInputStream inZip = new ZipInputStream(in)) {
            ZipEntry zipEntry;
            call.onComplete("开始解压文件");
            while ((zipEntry = inZip.getNextEntry()) != null) {
                unzipEntry(inZip, zipEntry, modelDistFolder, call);
            }
            call.onComplete("解压完成");

            return getModelPath(modelDistFolder);
        }
    }

    public static void unzipEntry(ZipInputStream inZip,
                                  ZipEntry zipEntry,
                                  File modelDistFolder,
                                  Callback<String> call) throws IOException {
        String szName = zipEntry.getName();
        File szFile;
        if (zipEntry.isDirectory()) {
            //获取部件的文件夹名
            szName = szName.substring(0, szName.length() - 1);
            szFile = new File(modelDistFolder, szName);

            call.onComplete("开始解压 " + szName + " 文件夹...");
            if (szFile.exists() && szFile.isDirectory()) {
                return;
            }
            if (!szFile.mkdirs()) {
                throw new IOException("文件夹创建失败");
            }
        } else {
            szFile = new File(modelDistFolder, szName);

            call.onComplete("开始解压 " + szName + " 文件...");
            if (szFile.exists()) {
                return;
            } else {
                szFile.getParentFile().mkdirs();
                if (!szFile.createNewFile()) {
                    throw new IOException("文件创建失败");
                }
            }
            // 获取文件的输出流
            byte[] buffer = new byte[1024];
            int len;
            try (FileOutputStream out = new FileOutputStream(szFile)) {
                // 读取（字节）字节到缓冲区
                while ((len = inZip.read(buffer)) != -1) {
                    // 从缓冲区（0）位置写入（字节）字节
                    out.write(buffer, 0, len);
                    out.flush();
                }
            }
            call.onComplete("文件解压成功");
        }

    }

    public interface Callback<R> {
        void onComplete(R result);
    }
}
