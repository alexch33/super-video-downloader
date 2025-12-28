/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.myAllVideoBrowser.geckoview_example;

import android.os.Parcel;
import android.os.Parcelable; // Import Parcelable
import androidx.annotation.NonNull;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

public class TabSession extends GeckoSession implements Parcelable {
  private String mTitle;
  private String mUri;
  public WebExtension.Action action;

  public TabSession() {
    super();
  }

  public TabSession(GeckoSessionSettings settings) {
    super(settings);
  }

  // Getters and Setters remain the same
  public String getTitle() {
    return mTitle == null || mTitle.isEmpty() ? "about:blank" : mTitle;
  }

  public void setTitle(String title) {
    this.mTitle = title;
  }

  public String getUri() {
    return mUri;
  }

  @Override
  public void loadUri(@NonNull String uri) {
    super.loadUri(uri);
    mUri = uri;
  }

  public void onLocationChange(@NonNull String uri) {
    mUri = uri;
  }


  // --- Parcelable Implementation ---

  // 2. Add a constructor that reads from a Parcel
  protected TabSession(Parcel in) {
    mTitle = in.readString();
    mUri = in.readString();
    // Note: GeckoSession is not Parcelable, so we cannot serialize it directly.
    // We can only serialize the state we need to restore it.
    // 'action' might also need to be made Parcelable if it's a custom class.
    // If WebExtension.Action is not parcelable, you cannot pass it directly.
    // Assuming it's either Parcelable or can be reconstructed.
    // If it's an enum, you can use: action = (WebExtension.Action) in.readSerializable();
  }

  // 3. Add the CREATOR static field
  public static final Creator<TabSession> CREATOR = new Creator<TabSession>() {
    @Override
    public TabSession createFromParcel(Parcel in) {
      return new TabSession(in);
    }

    @Override
    public TabSession[] newArray(int size) {
      return new TabSession[size];
    }
  };

  // 4. Implement describeContents
  @Override
  public int describeContents() {
    return 0; // Usually 0
  }

  // 5. Implement writeToParcel
  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(mTitle);
    dest.writeString(mUri);
    // Again, you can only write 'action' if it is a Parcelable or Serializable type.
    // If it's Serializable, you could use: dest.writeSerializable(action);
  }
}
