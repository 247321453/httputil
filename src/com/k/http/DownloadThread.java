package com.k.http;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;

import com.k.http.util.FileUtil;

public class DownloadThread extends Thread {

	private volatile DownloadListener listener;
	private volatile boolean isDownloading = false;
	private static final int MAX_THREAD = 3;
	private static final int BUFF_SIZE = 1024 * 512;

	private String mUrl;
	private String mFile;
	private File tmpFile;
	private DownloadInfo mDownloadInfo;
	private ExecutorService executor;
	private int cache_size;

	public DownloadThread(ExecutorService executor, String url, String file, int cache_size,
			DownloadListener listener) {
		super(file);
		this.executor = executor;
		this.mUrl = url;
		this.mFile = file;
		this.listener = listener;
		this.cache_size = cache_size;
		mDownloadInfo = new DownloadInfo(file + ".cfg", cache_size);
		tmpFile = new File(mFile + ".tmp");
	}

	public long getContentLength() {
		return mDownloadInfo.getLength();
	}

	@Override
	public void interrupt() {
		this.isDownloading = false;
		super.interrupt();
	}

	public long getProgress() {
		return tmpFile.length();
	}

	@Override
	public void run() {
		super.run();
		this.isDownloading = true;
		// 创建目录
		File dir = tmpFile.getParentFile();
		if (!dir.exists()) {
			dir.mkdirs();
		}

		boolean read = mDownloadInfo.createOrRead();
		System.out.println("pre download by read?" + read + " " + mDownloadInfo);
		// 需要下载的长度
		long alllength = getContentLength(mUrl);
		if (alllength < 0) {
			alllength = -alllength;
			mDownloadInfo.setLength(alllength);
			// 不支持断点
			if (mDownloadInfo.getLength() != alllength || (mDownloadInfo.getBlockCount() == 1 && alllength > BUFF_SIZE)
					|| mDownloadInfo.getBlockCount() == 0) {
				mDownloadInfo.setCache(cache_size);
				mDownloadInfo.createNew();
				System.out.println("createNew download..." + mDownloadInfo);
			}
		} else if (alllength > 0) {
			mDownloadInfo.setLength(alllength);
			if (mDownloadInfo.getLength() != alllength || mDownloadInfo.getBlockCount() != 1) {
				mDownloadInfo.setCache(alllength);
				mDownloadInfo.createNew();
				System.out.println("reset download..." + mDownloadInfo);
			}
		} else {
			if (listener != null) {
				listener.onFinish(mUrl, mFile, DownloadError.ERR_404);
			}
			return;
		}
		if (!isCompleted()) {
			System.out.println("start download..." + mDownloadInfo);
			if (listener != null) {
				listener.onStart(mUrl, mFile);
			}
			for (int i = 0; i < MAX_THREAD; i++) {
				if (startDownload(i)) {
					System.out.println("start thread ok " + i);
				} else {
					System.out.println("start thread fail " + i);
				}
			}
			System.out.println("end main thread ");
		}
	}

	private boolean startDownload(int start) {
		if (isDownloading) {
			if (isCompleted()) {
				return false;
			}
		}
		long[] b = new long[2];
		final int pos = mDownloadInfo.findblock(start, b);
		if (pos >= 0) {
			if (mDownloadInfo.isDownload(pos)) {
				System.out.println(pos + " is downloading");
				return false;
			}
			mDownloadInfo.updateStatu(pos, true);
			System.out.println("submit " + pos + " " + b[0] + "-" + b[1]);
			executor.submit(new Runnable() {

				@Override
				public void run() {
					System.out.println("download " + pos + " " + b[0] + "-" + b[1]);
					download(tmpFile, pos, b);
					mDownloadInfo.readBlock(pos);
					mDownloadInfo.updateStatu(pos, false);
					startDownload(pos + 1);
				}
			});
			return true;
		} else {
			System.out.println("no find null");
			return false;
		}
	}

	private boolean isCompleted() {
		// 有一个在下载都未完成
		if (!mDownloadInfo.isOk()) {
			return false;
		}
		System.out.println("is completed.");
		onfinish();
		executor.shutdown();
		return true;
	}

	private void download(File tmpFile, final int index, long[] b) {
		final long[] tmp = new long[2];
		tmp[0] = b[0];
		tmp[1] = b[1];
		final long total = tmp[1];
		final long start = tmp[0];
		if (start >= total) {
			mDownloadInfo.updateStatu(index, false);
			return;
		}
		HttpURLConnection httpURLConnection = null;
		RandomAccessFile output = null;
		InputStream input = null;
		try {
			// 断点续传测试
			URL url = new URL(mUrl);
			httpURLConnection = (HttpURLConnection) url.openConnection();
			httpURLConnection.setRequestProperty("Connection", "Keep-Alive");
			httpURLConnection.setRequestProperty("User-agent", System.getProperty("http.agent"));
			httpURLConnection.setConnectTimeout(60 * 1000);
			httpURLConnection.setReadTimeout(60 * 1000);
			httpURLConnection.setRequestProperty("Range", "bytes=" + start + "-" + total);
			// httpURLConnection.setAllowUserInteraction(true);
			int responseCode = httpURLConnection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
				// 支持断点续传
				input = httpURLConnection.getInputStream();
			} else if (responseCode == HttpURLConnection.HTTP_OK) {
				// 不支持断点续传
				input = httpURLConnection.getInputStream();
			}
			if (input == null) {
				if (listener != null) {
					listener.onFinish(mUrl, mFile, DownloadError.ERR_404);
				}
			} else {
				if (!tmpFile.exists()) {
					tmpFile.createNewFile();
				}
				output = new RandomAccessFile(tmpFile, "rws");
				output.seek(start);
				byte[] buffer = new byte[BUFF_SIZE];
				int length;
				long compeleteSize = start;
				while (isDownloading && (length = input.read(buffer)) != -1) {
					output.write(buffer, 0, length);
					compeleteSize += length;
					mDownloadInfo.updateBlock(index, new long[]{compeleteSize, total});
					if (listener != null) {
						listener.onProgress(mUrl, mFile, compeleteSize, total, false);
					}
				}
			}
		} catch (EOFException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			FileUtil.close(input);
			FileUtil.close(httpURLConnection);
			FileUtil.close(output);
		}
	}

	private void onfinish() {
		long alllength = mDownloadInfo.getLength();
		if (mFile == null) {
			System.err.println("file is null");
			if (listener != null) {
				listener.onFinish(mUrl, mFile, DownloadError.ERR_FILE);
			}
		} else {
			if (tmpFile.length() == alllength) {
				try {
					File rfile = new File(mFile);
					FileUtil.delete(rfile);
					FileUtil.createDirByFile(rfile);
					FileUtil.renameTo(tmpFile, rfile);
					FileUtil.delete(new File(mFile + ".cfg"));
					if (listener != null) {
						listener.onFinish(mUrl, mFile, DownloadError.ERR_NONE);
					}
				} catch (Exception e) {
					if (listener != null) {
						listener.onFinish(mUrl, mFile, DownloadError.ERR_OTHER);
					}
				}
			} else {
				System.err.println("alllength is bad " + tmpFile.length() + "/" + alllength);
				if (listener != null) {
					listener.onFinish(mUrl, mFile, DownloadError.ERR_FILE);
				}
			}
		}
		isDownloading = false;
	}

	private long getContentLength(String uri) {
		long length = 0;
		HttpURLConnection connection = null;
		try {
			URL url = new URL(uri);
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(60 * 1000);
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-agent", System.getProperty("http.agent"));
			connection.setRequestProperty("Range", "bytes=" + 1 + "-");
			connection.getResponseCode();
			int code = connection.getResponseCode();
			length = connection.getContentLengthLong();
			if (code == HttpURLConnection.HTTP_PARTIAL) {
				length = -(length + 1);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		return length;
	}
}
