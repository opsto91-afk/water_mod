package dev.orbit.workstation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class TermuxResult extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent result) {
    StationActivity a = StationActivity.current.get();
    if (a != null) a.onTermuxResult(result);
  }
}
