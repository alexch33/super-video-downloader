/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.myAllVideoBrowser.geckoview_example;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

public class TabSessionManager {
  private static ArrayList<TabSession> mTabSessions = new ArrayList<>();
  private int mCurrentSessionIndex = 0;
  private TabObserver mTabObserver;
  private TabsObserver mTabsObserver;
  private boolean mTrackingProtection;

  public interface TabObserver {
    void onCurrentSession(TabSession session);
  }

  public interface TabsObserver {
    void onOpenTab(TabSession session);
    void onCloseTab(TabSession session);
    void onSelectTab(TabSession session);
  }

  public TabSessionManager() {}

  public void unregisterWebExtension() {
    for (final TabSession session : mTabSessions) {
      session.action = null;
    }
  }

  public void setWebExtensionDelegates(
      WebExtension extension,
      WebExtension.ActionDelegate actionDelegate,
      WebExtension.MessageDelegate messageDelegate,
      WebExtension.SessionTabDelegate tabDelegate) {
    for (final TabSession session : mTabSessions) {
      final WebExtension.SessionController sessionController = session.getWebExtensionController();
      sessionController.setActionDelegate(extension, actionDelegate);
      sessionController.setTabDelegate(extension, tabDelegate);
      sessionController.setMessageDelegate(extension, messageDelegate, "browser");
    }
  }

  public void setUseTrackingProtection(boolean trackingProtection) {
    if (trackingProtection == mTrackingProtection) {
      return;
    }
    mTrackingProtection = trackingProtection;

    for (final TabSession session : mTabSessions) {
      session.getSettings().setUseTrackingProtection(trackingProtection);
    }
  }

  public void setTabObserver(TabObserver observer) {
    mTabObserver = observer;
  }

  public void setTabsObserver(TabsObserver observer) {
    mTabsObserver = observer;
  }

  public void addSession(TabSession session) {
    mTabSessions.add(session);
  }

  public TabSession getSession(int index) {
    if (index >= mTabSessions.size() || index < 0) {
      return null;
    }
    return mTabSessions.get(index);
  }

  public TabSession getCurrentSession() {
    return getSession(mCurrentSessionIndex);
  }

  public TabSession getSession(GeckoSession session) {
    int index = mTabSessions.indexOf(session);
    if (index == -1) {
      return null;
    }
    return getSession(index);
  }

  public void setCurrentSession(TabSession session) {
    int index = mTabSessions.indexOf(session);
    if (index == -1) {
      mTabSessions.add(session);
      index = mTabSessions.size() - 1;
    }
    mCurrentSessionIndex = index;

    if (mTabObserver != null) {
      mTabObserver.onCurrentSession(session);
    }

    if (mTabsObserver != null) {
      mTabsObserver.onSelectTab(session);
    }
  }

  private boolean isCurrentSession(TabSession session) {
    return session == getCurrentSession();
  }

  public void closeSession(@Nullable TabSession session) {
    if (session == null) {
      return;
    }
    if (isCurrentSession(session) && mCurrentSessionIndex == mTabSessions.size() - 1) {
      --mCurrentSessionIndex;
    }
    mTabsObserver.onCloseTab(session);
    session.close();
    mTabSessions.remove(session);
  }

  public TabSession newSession(GeckoSessionSettings settings) {
    TabSession tabSession = new TabSession(settings);
    mTabSessions.add(tabSession);
    mTabsObserver.onOpenTab(tabSession);
    return tabSession;
  }

  public int sessionCount() {
    return mTabSessions.size();
  }

  public ArrayList<TabSession> getSessions() {
    return mTabSessions;
  }
}
