package com.myAllVideoBrowser.util.downloaders.generic_downloader.models;

import androidx.annotation.NonNull;

import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.Video.Type;

public class VideoTaskItem implements Cloneable {

    private String mUrl;                 // Download video URL
    private String mCoverUrl;            // Cover image URL
    private String mCoverPath;           // Cover image local path
    private String mTitle;               // Video title
    private String mGroupName;           // Download group name
    private long mDownloadCreateTime;    // Download creation time
    private int mTaskState;              // Current task state (e.g., DOWNLOADING, PAUSE)
    private String mMimeType;            // Mime type of the video URL
    private String mFinalUrl;            // URL after any 30x redirects
    private int mErrorCode;              // Error code if the download fails
    private int mVideoType;              // File type (e.g., HLS, MPD)
    private int mTotalTs;                // Total number of M3U8 segments
    private int mCurTs;                  // Number of M3U8 segments currently cached
    private float mSpeed;                // Current download speed
    private float mPercent;              // Current download percentage (0-100)
    private long mDownloadSize;          // Size downloaded so far
    private long mTotalSize;             // Total file size
    private String mFileHash;            // MD5 hash of the file name
    private String mSaveDir;             // Directory where the file is saved
    private boolean mIsCompleted;        // Is the download complete?
    private boolean mIsInDatabase;       // Is the task saved in the database?
    private long mLastUpdateTime;        // Last time the database was updated
    private String mFileName;            // File name
    private String mFilePath;            // Full file path (including name)
    private boolean mPaused;             // Is the task paused?
    private boolean mIsLive;             // Is it a live stream?
    private String mErrorMessage;        // Error message on failure
    private String mId;                  // Unique ID for the task
    private String lineInfo;             // Extra info line for the UI (e.g., "Merging...")
    private Long accumulatedDuration = 0L; // Accumulated duration for live streams

    public VideoTaskItem(String url) {
        this(url, "", "", "");
    }

    public VideoTaskItem(String url, String coverUrl, String title, String groupName) {
        mUrl = url;
        mCoverUrl = coverUrl;
        mTitle = title;
        mGroupName = groupName;
        mTaskState = VideoTaskState.DEFAULT;
    }

    /**
     * Creates and returns a deep copy of this VideoTaskItem.
     * All fields are copied to the new object.
     */
    @NonNull
    @Override
    public VideoTaskItem clone() {
        VideoTaskItem clonedItem = new VideoTaskItem(this.mUrl);
        clonedItem.mCoverUrl = this.mCoverUrl;
        clonedItem.mCoverPath = this.mCoverPath;
        clonedItem.mTitle = this.mTitle;
        clonedItem.mGroupName = this.mGroupName;
        clonedItem.mDownloadCreateTime = this.mDownloadCreateTime;
        clonedItem.mTaskState = this.mTaskState;
        clonedItem.mMimeType = this.mMimeType;
        clonedItem.mFinalUrl = this.mFinalUrl;
        clonedItem.mErrorCode = this.mErrorCode;
        clonedItem.mVideoType = this.mVideoType;
        clonedItem.mTotalTs = this.mTotalTs;
        clonedItem.mCurTs = this.mCurTs;
        clonedItem.mSpeed = this.mSpeed;
        clonedItem.mPercent = this.mPercent;
        clonedItem.mDownloadSize = this.mDownloadSize;
        clonedItem.mTotalSize = this.mTotalSize;
        clonedItem.mFileHash = this.mFileHash;
        clonedItem.mSaveDir = this.mSaveDir;
        clonedItem.mIsCompleted = this.mIsCompleted;
        clonedItem.mIsInDatabase = this.mIsInDatabase;
        clonedItem.mLastUpdateTime = this.mLastUpdateTime;
        clonedItem.mFileName = this.mFileName;
        clonedItem.mFilePath = this.mFilePath;
        clonedItem.mPaused = this.mPaused;
        clonedItem.mIsLive = this.mIsLive;
        clonedItem.mErrorMessage = this.mErrorMessage;
        clonedItem.mId = this.mId;
        clonedItem.lineInfo = this.lineInfo;
        clonedItem.accumulatedDuration = this.accumulatedDuration;

        return clonedItem;
    }


    // --- Other Methods (unchanged, but getters/setters listed for completeness) ---

    public float getPercentFromBytes() {
        if (getTotalSize() == 0) return 0;
        return (1F * getDownloadSize() / getTotalSize()) * 100F;
    }

    public float getPercentFromBytes(long downloadSize, long totalSize) {
        if (totalSize == 0) return 0;
        return (1F * downloadSize / totalSize) * 100F;
    }

    public boolean isLive() {
        return mIsLive;
    }

    public void setIsLive(boolean isLive) {
        this.mIsLive = isLive;
    }

    public void setLineInfo(String info) {
        this.lineInfo = info;
    }

    public String getLineInfo() {
        return this.lineInfo;
    }

    public void setMId(String id) {
        mId = id;
    }

    public String getMId() {
        return mId;
    }

    public void setErrorMessage(String message) {
        mErrorMessage = message;
    }

    public String getErrorMessage() {
        return mErrorMessage;
    }

    public String getUrl() {
        return mUrl;
    }

    public void setCoverUrl(String coverUrl) {
        mCoverUrl = coverUrl;
    }

    public String getCoverUrl() {
        return mCoverUrl;
    }

    public void setCoverPath(String coverPath) {
        mCoverPath = coverPath;
    }

    public String getCoverPath() {
        return mCoverPath;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setGroupName(String groupName) {
        mGroupName = groupName;
    }

    public String getGroupName() {
        return mGroupName;
    }

    public void setDownloadCreateTime(long time) {
        mDownloadCreateTime = time;
    }

    public long getDownloadCreateTime() {
        return mDownloadCreateTime;
    }

    public void setTaskState(int state) {
        mTaskState = state;
    }

    public int getTaskState() {
        return mTaskState;
    }

    public void setMimeType(String mimeType) {
        mMimeType = mimeType;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public void setFinalUrl(String finalUrl) {
        mFinalUrl = finalUrl;
    }

    public String getFinalUrl() {
        return mFinalUrl;
    }

    public void setErrorCode(int errorCode) {
        mErrorCode = errorCode;
    }

    public int getErrorCode() {
        return mErrorCode;
    }

    public void setVideoType(int type) {
        mVideoType = type;
    }

    public int getVideoType() {
        return mVideoType;
    }

    public void setTotalTs(int count) {
        mTotalTs = count;
    }

    public int getTotalTs() {
        return mTotalTs;
    }

    public void setCurTs(int count) {
        mCurTs = count;
    }

    public int getCurTs() {
        return mCurTs;
    }

    public void setSpeed(float speed) {
        mSpeed = speed;
    }

    public float getSpeed() {
        return mSpeed;
    }

    public void setPercent(float percent) {
        mPercent = percent;
    }

    public float getPercent() {
        return mPercent;
    }

    public void setDownloadSize(long size) {
        mDownloadSize = size;
    }

    public long getDownloadSize() {
        return mDownloadSize;
    }

    public void setTotalSize(long size) {
        mTotalSize = size;
    }

    public long getTotalSize() {
        return mTotalSize;
    }

    public void setFileHash(String md5) {
        mFileHash = md5;
    }

    public String getFileHash() {
        return mFileHash;
    }

    public void setSaveDir(String path) {
        mSaveDir = path;
    }

    public String getSaveDir() {
        return mSaveDir;
    }

    public void setIsCompleted(boolean completed) {
        mIsCompleted = completed;
    }

    public boolean isCompleted() {
        return mIsCompleted;
    }

    public void setIsInDatabase(boolean in) {
        mIsInDatabase = in;
    }

    public boolean isInDatabase() {
        return mIsInDatabase;
    }

    public void setLastUpdateTime(long time) {
        mLastUpdateTime = time;
    }

    public long getLastUpdateTime() {
        return mLastUpdateTime;
    }

    public void setFileName(String name) {
        mFileName = name;
    }

    public String getFileName() {
        return mFileName;
    }

    public void setFilePath(String path) {
        mFilePath = path;
    }

    public String getFilePath() {
        return mFilePath;
    }

    public void setPaused(boolean paused) {
        mPaused = paused;
    }

    public boolean isPaused() {
        return mPaused;
    }

    public boolean isRunningTask() {
        return mTaskState == VideoTaskState.DOWNLOADING;
    }

    public boolean isPendingTask() {
        return mTaskState == VideoTaskState.PENDING || mTaskState == VideoTaskState.PREPARE;
    }

    public boolean isErrorState() {
        return mTaskState == VideoTaskState.ERROR;
    }

    public boolean isSuccessState() {
        return mTaskState == VideoTaskState.SUCCESS;
    }

    public boolean isInterruptTask() {
        return mTaskState == VideoTaskState.PAUSE || mTaskState == VideoTaskState.ERROR;
    }

    public boolean isInitialTask() {
        return mTaskState == VideoTaskState.DEFAULT;
    }

    public boolean isHlsType() {
        return mVideoType == Type.HLS_TYPE;
    }

    public void reset() {
        mDownloadCreateTime = 0L;
        mMimeType = null;
        mErrorCode = 0;
        mVideoType = Type.DEFAULT;
        mTaskState = VideoTaskState.DEFAULT;
        mSpeed = 0.0f;
        mPercent = 0.0f;
        mDownloadSize = 0;
        mTotalSize = 0;
        mFileName = "";
        mFilePath = "";
        mCoverUrl = "";
        mCoverPath = "";
        mTitle = "";
        mGroupName = "";
        mErrorMessage = null;
        lineInfo = null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        VideoTaskItem that = (VideoTaskItem) obj;
        // A more robust equals: check a unique ID if it exists, otherwise the URL.
        if (mId != null) {
            return mId.equals(that.mId);
        }
        return mUrl.equals(that.mUrl);
    }

    @Override
    public int hashCode() {
        // A corresponding robust hashCode
        if (mId != null) {
            return mId.hashCode();
        }
        return mUrl.hashCode();
    }


    @Override
    public String toString() {
        return "VideoTaskItem[" + "mId='" + mId + '\'' +
                ", mUrl='" + mUrl + '\'' +
                ", mTitle='" + mTitle + '\'' +
                ", mTaskState=" + mTaskState +
                ", mPercent=" + mPercent +
                ", mDownloadSize=" + mDownloadSize +
                ", mTotalSize=" + mTotalSize +
                ", mFilePath='" + mFilePath + '\'' +
                ", isLive=" + mIsLive +
                ']';
    }

    public Long getAccumulatedDuration() {
        return accumulatedDuration;
    }

    public void setAccumulatedDuration(Long accumulatedDuration) {
        this.accumulatedDuration = accumulatedDuration;
    }
}
