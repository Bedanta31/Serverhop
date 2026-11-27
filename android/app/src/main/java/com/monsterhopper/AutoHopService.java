package com.monsterhopper;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

public class AutoHopService extends Service {
    private static final String CHANNEL = "monster_hopper_auto";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PLACE_ID = "98664161516921";
    private static final String PREFS = "hopper";
    private static final String API_BASE = "https://serverhop-production.up.railway.app";
    private static final int DAILY_LIMIT = 50;
    private static volatile boolean running;
    private Handler handler;
    private Runnable task;
    private Runnable historyResetTask;
    private WindowManager windowManager;
    private View overlay;
    private boolean busy;
    private int failureCount;

    public static boolean isRunning() { return running; }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    @Override public void onCreate() { super.onCreate(); handler = new Handler(Looper.getMainLooper()); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if ("STOP".equals(action)) { stopHop(); return START_NOT_STICKY; }
        if ("PAUSE".equals(action)) { pauseHop(); return START_NOT_STICKY; }
        if ("TOGGLE".equals(action)) { if (running) stopHop(); else startHop(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, notification("Starting server discovery"));
        if (!running) startHop();
        return START_STICKY;
    }
    private void startHop() {
        running = true;
        showOverlay();
        updateOverlayLabel();
        scheduleHistoryBoundaryReset();
        broadcastState();
        updateNotification("Searching public servers");
        attemptHop();
    }
    private long getInterval() { return getSharedPreferences(PREFS, MODE_PRIVATE).getLong("interval", 15000L); }
    private void attemptHop() {
        if (!running || busy) return;
        busy = true;
        new Thread(() -> {
            String chosen = null; int selectedPlayers = Integer.MAX_VALUE;
            try {
                clearExpiredHistory();
                JSONObject hopPayload=getJson(API_BASE+"/api/hop-limit");JSONObject hop=hopPayload.optJSONObject("data");if(hop!=null&&hop.optBoolean("limitReached")){busy=false;handler.post(()->{updateNotification("Daily hop limit reached · resets at 00:00 UTC");stopHop();});return;}
                JSONObject payload=getJson(API_BASE+"/api/servers?limit=100&maxPages=5");if(!payload.optBoolean("success"))throw new IOException(payload.optString("message","Server discovery failed"));
                JSONObject data=payload.optJSONObject("data");JSONArray servers=data==null?null:data.optJSONArray("servers");
                if(servers!=null)for(int i=0;i<servers.length();i++){JSONObject server=servers.optJSONObject(i);if(server==null)continue;String id=server.optString("jobId","");int playing=server.optInt("players",-1),maximum=server.optInt("maxPlayers",0);if(id.isEmpty()||playing<0||maximum<=playing||wasVisited(id)||id.equals(getSharedPreferences(PREFS,MODE_PRIVATE).getString("pendingJoin","")))continue;if(playing<selectedPlayers){selectedPlayers=playing;chosen=id;}}
                if(data!=null&&data.optBoolean("partial")){long retry=Math.max(0,data.optLong("retryAfterMs",0));if(retry>0)failureCount=Math.min(failureCount+1,6);}
                failureCount=0;
            } catch (Exception e) {
                busy = false; failureCount=Math.min(failureCount+1,6); handler.post(() -> { if (running) { updateNotification("Discovery error · "+e.getMessage()); scheduleNextWithBackoff(); } }); return;
            }
            final String selected = chosen; final int count = selectedPlayers;
            handler.post(() -> {
                busy = false; if (!running) return;
                if (selected == null) { updateNotification("No new servers found · waiting"); scheduleNext(); return; }
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("pendingJoin",selected).putString("activeServer",selected).putInt("activePlayers",count).apply();broadcastState();
                updateNotificationWithJoin("Server found · " + count + " players", selected);
                scheduleNext();
            });
        }, "monster-hopper-discovery").start();
    }
    private void scheduleNext() {
        if (!running) return; if (task != null) handler.removeCallbacks(task);
        task = this::attemptHop; handler.postDelayed(task, Math.max(5000L, getInterval()));
    }
    private void scheduleNextWithBackoff(){if(!running)return;if(task!=null)handler.removeCallbacks(task);long multiplier=1L<<Math.min(5,Math.max(0,failureCount-1));long delay=Math.min(300000L,Math.max(getInterval(),getInterval()*multiplier));task=this::attemptHop;handler.postDelayed(task,delay);}
    private JSONObject getJson(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setRequestProperty("Accept","application/json");int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();StringBuilder body=new StringBuilder();if(in!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,"UTF-8"))){String line;while((line=reader.readLine())!=null)body.append(line);}c.disconnect();JSONObject result=new JSONObject(body.toString());if(code<200||code>=300)throw new IOException("HTTP "+code);return result;}
    private String historyWindowKey(){Calendar now=Calendar.getInstance();return String.format(Locale.US,"%04d-%02d-%02dT%02d:%02d",now.get(Calendar.YEAR),now.get(Calendar.MONTH)+1,now.get(Calendar.DAY_OF_MONTH),now.get(Calendar.HOUR_OF_DAY),(now.get(Calendar.MINUTE)/15)*15);}
    private void scheduleHistoryBoundaryReset(){if(historyResetTask!=null)handler.removeCallbacks(historyResetTask);historyResetTask=()->{clearExpiredHistory();broadcastState();scheduleHistoryBoundaryReset();};Calendar next=Calendar.getInstance();next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);next.set(Calendar.MINUTE,(next.get(Calendar.MINUTE)/15+1)*15);handler.postDelayed(historyResetTask,Math.max(1000,next.getTimeInMillis()-System.currentTimeMillis()));}
    private void clearExpiredHistory() {
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);String window=historyWindowKey();if(!window.equals(prefs.getString("historyWindow","")))prefs.edit().remove("visited").putInt("attemptCount",0).putString("historyWindow",window).apply();
    }
    private boolean wasVisited(String id) {
        clearExpiredHistory(); Set<String> visited = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("visited", Collections.emptySet()); return getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("skipAttempted",true)&&visited.contains(id);
    }
    private void pauseHop() {
        running = false; if (task != null) { handler.removeCallbacks(task); task = null; }
        updateOverlayLabel(); broadcastState(); updateNotification("Auto-Hop paused");
    }
    private void stopHop() {
        running = false; busy = false; if (handler != null && task != null) { handler.removeCallbacks(task); task = null; } if(handler!=null&&historyResetTask!=null){handler.removeCallbacks(historyResetTask);historyResetTask=null;}
        removeOverlay(); broadcastState(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) { NotificationChannel channel = new NotificationChannel(CHANNEL, "Monster Hopper", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(channel); }
    }
    private PendingIntent joinPending(String id) {
        Intent join = new Intent(this, MainActivity.class); join.setAction("JOIN_SERVER"); join.putExtra("serverId", id); join.setData(Uri.parse("monsterhopper://join/" + Uri.encode(id))); join.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT; if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, id.hashCode(), join, flags);
    }
    private Notification.Builder builder(String message) {
        Intent stop = new Intent(this, AutoHopService.class); stop.setAction("STOP");
        Intent pause = new Intent(this, AutoHopService.class); pause.setAction("PAUSE");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT; if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, flags), pauseAction = PendingIntent.getService(this, 2, pause, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Monster Hopper").setContentText(message).setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseAction).addAction(android.R.drawable.ic_delete, "Stop", stopAction);
        return b;
    }
    private Notification notification(String message) { return builder(message).build(); }
    private void updateNotification(String text) { NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE); nm.notify(NOTIFICATION_ID, notification(text)); }
    private void updateNotificationWithJoin(String text, String id) {
        Notification.Builder b = builder(text).addAction(android.R.drawable.ic_menu_view, "Join server", joinPending(id));
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, b.build());
    }
    private void broadcastState() { Intent state = new Intent("com.monsterhopper.STATE"); state.setPackage(getPackageName()); sendBroadcast(state); }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        TextView bubble = new TextView(this); bubble.setText(running ? "STOP AUTO-HOP" : "START AUTO-HOP"); bubble.setTextColor(Color.WHITE); bubble.setTextSize(13); bubble.setTypeface(Typeface.DEFAULT, Typeface.BOLD); bubble.setGravity(Gravity.CENTER); bubble.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(104, 74, 204)); bg.setCornerRadius(dp(30)); bg.setStroke(dp(1), Color.rgb(175, 151, 255)); bubble.setBackground(bg); bubble.setElevation(dp(8));
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END; params.x = dp(18); params.y = dp(180);
        bubble.setOnTouchListener(new View.OnTouchListener() { float downX, downY; int originX, originY; boolean moved;
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) { downX=event.getRawX(); downY=event.getRawY(); originX=params.x; originY=params.y; moved=false; return true; }
                if (event.getAction() == MotionEvent.ACTION_MOVE) { float dx=event.getRawX()-downX, dy=event.getRawY()-downY; if (Math.abs(dx)+Math.abs(dy)>dp(6)) moved=true; params.x=originX-(int)dx; params.y=originY+(int)dy; try { windowManager.updateViewLayout(bubble,params); } catch (Exception ignored) {} return true; }
                if (event.getAction() == MotionEvent.ACTION_UP) { if (!moved) { if (running) pauseHop(); else startHop(); } return true; } return true;
            }
        });
        overlay = bubble; try { windowManager.addView(overlay, params); } catch (Exception e) { overlay = null; }
    }
    private void updateOverlayLabel() { if (overlay instanceof TextView) ((TextView)overlay).setText(running ? "STOP AUTO-HOP" : "START AUTO-HOP"); }
    private void removeOverlay() { if (overlay != null && windowManager != null) { try { windowManager.removeView(overlay); } catch (Exception ignored) {} overlay = null; } }
    @Override public void onDestroy() { running=false; if (handler != null && task != null) handler.removeCallbacks(task);if(handler!=null&&historyResetTask!=null)handler.removeCallbacks(historyResetTask);removeOverlay(); broadcastState(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
