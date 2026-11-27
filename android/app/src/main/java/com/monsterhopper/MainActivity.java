package com.monsterhopper;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.content.ClipData;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String PLACE_ID = "98664161516921";
    private static final String PREFS = "hopper";
    private static final String API_BASE = "https://serverhop-production.up.railway.app";
    private static final int REQUEST_NOTIFICATIONS = 41;
    private static final int REQUEST_OVERLAY = 42;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable historyClock = new Runnable(){@Override public void run(){ensureHistoryWindow();renderHistory();Calendar next=Calendar.getInstance();next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);next.set(Calendar.MINUTE,(next.get(Calendar.MINUTE)/15+1)*15);uiHandler.postDelayed(this,Math.max(1000,next.getTimeInMillis()-System.currentTimeMillis()));}};
    private LinearLayout list, historyList;
    private TextView status, stats, historySummary;
    private EditText intervalInput;
    private Button moreButton;
    private Button previousButton;
    private EditText searchInput;
    private Spinner sortInput;
    private CheckBox skipAttemptedInput, pauseFailureInput;
    private TextView metrics, refreshState;
    private ImageView profileAvatar;
    private final ArrayList<JSONObject> serverData = new ArrayList<>();
    private int page = 1;
    private static final int PAGE_SIZE = 6;
    private String sortMode = "Fewest players";
    private boolean joinPending;
    private String cursor = "";
    private boolean loading;
    private boolean appendMode;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateStatus(); renderHistory(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 19));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 19));
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUI();
        IntentFilter stateFilter = new IntentFilter("com.monsterhopper.STATE");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, stateFilter);
        requestPermissionsIfNeeded();
        renderHistory();
        loadProfileAvatar();
        openServerFromIntent(getIntent());
        fetchServers(false);
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openServerFromIntent(intent); }
    private void openServerFromIntent(Intent intent) {
        if (intent != null && "JOIN_SERVER".equals(intent.getAction())) {
            String id = intent.getStringExtra("serverId");
            if (id != null && !id.isEmpty()) joinServer(id);
        }
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private int color(String hex) { return Color.parseColor(hex); }
    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private TextView text(String value, int size, int tint, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(tint);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void buildUI() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(color("#090B13"));
        LinearLayout root = column(); root.setPadding(dp(20), dp(24), dp(20), dp(32)); scroll.addView(root);
        TextView eyebrow = text("MONSTER HOPPER  /  ROBLOX", 12, color("#43D5E6"), true); root.addView(eyebrow);
        LinearLayout profile = new LinearLayout(this); profile.setGravity(Gravity.CENTER_VERTICAL); profile.setPadding(0,dp(8),0,dp(4)); profileAvatar = new ImageView(this); profileAvatar.setImageResource(android.R.drawable.sym_def_app_icon); profileAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP); profileAvatar.setLayoutParams(new LinearLayout.LayoutParams(dp(34),dp(34))); profile.addView(profileAvatar); profile.addView(text("  Made by VedantxKMG · Roblox profile",12,color("#A4ADC0"),false)); profile.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.roblox.com/users/8346955610/profile")));}catch(Exception ignored){}}); root.addView(profile);
        TextView title = text("Find your next\nmonster server.", 30, Color.WHITE, true); title.setPadding(0, dp(8), 0, dp(6)); root.addView(title);
        TextView subtitle = text("A calmer way to browse and hop.", 14, color("#9BA3B7"), false); root.addView(subtitle);
        LinearLayout statusCard = column(); statusCard.setPadding(dp(16), dp(14), dp(16), dp(14)); statusCard.setBackground(shape(color("#121725"), color("#252D40"), 18));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.setMargins(0, dp(22), 0, dp(12)); statusCard.setLayoutParams(cardParams);
        status = text("Ready to explore", 15, Color.WHITE, true); statusCard.addView(status);
        stats = text("Loading public servers…", 13, color("#A4ADC0"), false); stats.setPadding(0, dp(6), 0, 0); statusCard.addView(stats); root.addView(statusCard);
        LinearLayout settings = column(); settings.setPadding(dp(16), dp(15), dp(16), dp(15)); settings.setBackground(shape(color("#121725"), color("#252D40"), 18));
        TextView settingTitle = text("AUTO-HOP CONTROL", 12, color("#43D5E6"), true); settings.addView(settingTitle);
        TextView label = text("Delay between server checks (seconds)", 13, color("#C3CAD8"), false); label.setPadding(0, dp(12), 0, dp(4)); settings.addView(label);
        intervalInput = new EditText(this); intervalInput.setSingleLine(true); intervalInput.setInputType(2); intervalInput.setTextColor(Color.WHITE); intervalInput.setTextSize(15); intervalInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        intervalInput.setBackground(shape(color("#0B0F19"), color("#30394D"), 12)); intervalInput.setText(String.valueOf(getSharedPreferences(PREFS, MODE_PRIVATE).getLong("interval", 15000L) / 1000)); settings.addView(intervalInput);
        Button save = button("Save interval", false); save.setOnClickListener(v -> saveInterval()); settings.addView(save);
        Button start = button("Start Auto-Hop", true); start.setOnClickListener(v -> beginHop()); settings.addView(start);
        Button resume = button("Resume Auto-Hop", false); resume.setOnClickListener(v -> startServiceIntent()); settings.addView(resume);
        Button pause = button("Pause Auto-Hop", false); pause.setOnClickListener(v -> serviceAction("PAUSE")); settings.addView(pause);
        Button stop = button("Stop Auto-Hop", false); stop.setOnClickListener(v -> serviceAction("STOP")); settings.addView(stop); root.addView(settings);
        TextView notice = text("Android requires a tap to open Roblox from the background. Auto-Hop will notify you when it finds a server.", 12, color("#8F99AC"), false); notice.setPadding(dp(3), dp(10), dp(3), dp(10)); root.addView(notice);
        metrics = text("Daily hops: —/50  ·  Scanned: 0  ·  Skipped: 0  ·  Attempts: 0", 12, color("#A4ADC0"), false); metrics.setPadding(0, dp(5), 0, dp(8)); root.addView(metrics);
        LinearLayout tools = new LinearLayout(this); tools.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("Refresh", false); refresh.setOnClickListener(v -> fetchServers(true)); tools.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("Clear visited", false); clear.setOnClickListener(v -> clearHistory()); tools.addView(clear, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(tools);
        refreshState = text("Connecting to server service…", 12, color("#8F99AC"), false); refreshState.setPadding(0, dp(5), 0, dp(8)); root.addView(refreshState);
        searchInput = new EditText(this); searchInput.setSingleLine(true); searchInput.setTextColor(Color.WHITE); searchInput.setHintTextColor(color("#778198")); searchInput.setHint("Search server IDs"); searchInput.setPadding(dp(12), dp(9), dp(12), dp(9)); searchInput.setBackground(shape(color("#0B0F19"), color("#30394D"), 12)); searchInput.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){ page=1; renderServerPage(); } public void afterTextChanged(android.text.Editable e){} }); root.addView(searchInput);
        sortInput = new Spinner(this); String[] sortLabels = {"Fewest players", "Most players", "Lowest occupancy", "Best FPS", "Lowest ping"}; sortInput.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, sortLabels)); sortInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p, View v,int position,long id){ sortMode=sortLabels[position]; page=1; renderServerPage(); }}); root.addView(sortInput);
        skipAttemptedInput = new CheckBox(this); skipAttemptedInput.setText("Skip servers already tried"); skipAttemptedInput.setTextColor(color("#D8DCE7")); skipAttemptedInput.setButtonTintList(android.content.res.ColorStateList.valueOf(color("#39D8E8"))); skipAttemptedInput.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("skipAttempted", true)); skipAttemptedInput.setOnCheckedChangeListener((v, checked) -> { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("skipAttempted", checked).apply(); page=1; renderServerPage(); }); root.addView(skipAttemptedInput);
        pauseFailureInput = new CheckBox(this); pauseFailureInput.setText("Pause Auto-Hop if a launch request fails"); pauseFailureInput.setTextColor(color("#D8DCE7")); pauseFailureInput.setButtonTintList(android.content.res.ColorStateList.valueOf(color("#39D8E8"))); pauseFailureInput.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("pauseOnFailure", false)); pauseFailureInput.setOnCheckedChangeListener((v, checked) -> getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("pauseOnFailure", checked).apply()); root.addView(pauseFailureInput);
        TextView serversHeading = text("PUBLIC SERVERS", 12, color("#43D5E6"), true); serversHeading.setPadding(2, dp(18), 0, dp(4)); root.addView(serversHeading);
        list = column(); root.addView(list);
        LinearLayout paging = new LinearLayout(this); paging.setOrientation(LinearLayout.HORIZONTAL); previousButton = button("Previous", false); previousButton.setOnClickListener(v -> {if(page>1){page--;renderServerPage();}}); paging.addView(previousButton,new LinearLayout.LayoutParams(0,-2,1)); moreButton = button("Next", false); moreButton.setOnClickListener(v -> {page++;renderServerPage();}); paging.addView(moreButton,new LinearLayout.LayoutParams(0,-2,1)); root.addView(paging);
        TextView historyHeading = text("VISITED SERVERS", 12, color("#43D5E6"), true); historyHeading.setPadding(2, dp(22), 0, dp(4)); root.addView(historyHeading);
        historySummary = text("History resets at :00, :15, :30 and :45 local time.", 12, color("#8F99AC"), false); historySummary.setPadding(0, dp(4), 0, dp(8)); root.addView(historySummary);
        historyList = column(); root.addView(historyList);
        setContentView(scroll);
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : color("#D8DCE7")); b.setBackground(shape(primary ? color("#167E98") : color("#1A2130"), primary ? 0 : color("#30394D"), 13));
        b.setMinHeight(dp(46)); b.setPadding(dp(12), dp(4), dp(12), dp(4)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(4), 0, dp(4)); b.setLayoutParams(p); return b;
    }
    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }
    private void beginHop() {
        saveInterval();
        if (!Settings.canDrawOverlays(this)) {
            try { Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())); startActivityForResult(permission, REQUEST_OVERLAY); }
            catch (Exception e) { status.setText("Enable display over other apps in Android Settings"); }
            return;
        }
        startServiceIntent();
    }
    private void startServiceIntent() {
        Intent intent = new Intent(this, AutoHopService.class);
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent); status.setText("Auto-Hop is running"); }
        catch (Exception e) { status.setText("Could not start Auto-Hop: " + e.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == REQUEST_OVERLAY) { if (Settings.canDrawOverlays(this)) startServiceIntent(); else status.setText("Overlay permission is needed for the floating control"); } }
    @Override protected void onResume() { super.onResume(); ensureHistoryWindow(); renderHistory();uiHandler.removeCallbacks(historyClock);uiHandler.post(historyClock);if (AutoHopService.isRunning()) updateStatus(); }
    @Override protected void onPause(){uiHandler.removeCallbacks(historyClock);super.onPause();}
    private void serviceAction(String action) {
        if ("STOP".equals(action)) { stopService(new Intent(this, AutoHopService.class)); status.setText("Auto-Hop stopped"); return; }
        if ("PAUSE".equals(action) && !AutoHopService.isRunning()) { status.setText("Auto-Hop is already stopped"); return; }
        Intent intent = new Intent(this, AutoHopService.class); intent.setAction(action);
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent); status.setText("Auto-Hop paused"); } catch (Exception e) { status.setText("Auto-Hop is not running"); }
    }
    private void updateStatus() { SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);String server=p.getString("activeServer","");status.setText(AutoHopService.isRunning()?(server.isEmpty()?"Auto-Hop is running":"Current candidate · "+p.getInt("activePlayers",0)+" players · "+server):"Auto-Hop is stopped"); }
    private void saveInterval() {
        try { int seconds = Math.max(15, Math.min(120, Integer.parseInt(intervalInput.getText().toString().trim()))); getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("interval", seconds * 1000L).apply(); intervalInput.setText(String.valueOf(seconds)); status.setText("Interval saved · " + seconds + " seconds"); }
        catch (Exception e) { status.setText("Enter a valid interval from 15 to 120 seconds"); }
    }
    private void fetchServers(boolean force) {
        if (loading) return;
        loading = true; status.setText("Refreshing public server data…"); refreshState.setText("Loading up to 500 servers from Monster Hopper API…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = API_BASE + "/api/servers?limit=100&maxPages=5" + (force ? "&force=true" : "");
                connection = (HttpURLConnection)new URL(endpoint).openConnection(); connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setRequestProperty("Accept", "application/json");
                int responseCode = connection.getResponseCode(); String body = readBody(connection, responseCode);
                JSONObject payload = new JSONObject(body); if (responseCode < 200 || responseCode >= 300 || !payload.optBoolean("success")) throw new IOException(payload.optJSONObject("data") == null ? payload.optString("message", "Server request failed") : payload.optJSONObject("data").optString("error", "Server request failed"));
                JSONObject data = payload.optJSONObject("data"); if(data==null)throw new IOException("The server API returned no data"); JSONArray servers = data.optJSONArray("servers"); ArrayList<JSONObject> rows = new ArrayList<>(); HashSet<String> ids = new HashSet<>();
                if (servers != null) for (int i=0;i<servers.length();i++) { JSONObject server=servers.optJSONObject(i); if(server==null)continue; String id=server.optString("jobId", server.optString("id", "")).trim(); if(id.isEmpty() || !ids.add(id.toLowerCase(Locale.ROOT)))continue; server.put("jobId",id); rows.add(server); }
                JSONObject hopData=null;try{hopData=getJson(API_BASE + "/api/hop-limit", null).optJSONObject("data");}catch(Exception ignored){}
                final JSONObject finalHopData=hopData;
                runOnUiThread(() -> { serverData.clear(); serverData.addAll(rows); stats.setText(rows.size() + " servers loaded · " + data.optString("source", "fresh") + (data.optBoolean("partial") ? " · partial" : "")); refreshState.setText("API online · " + data.optInt("totalServers",rows.size()) + " scanned" + (data.optBoolean("partial") ? " · rate limited or partial" : "")); if(finalHopData!=null) updateHopStats(finalHopData); page=1; renderServerPage(); status.setText("Server discovery updated"); loading=false; });
            } catch (Exception e) { runOnUiThread(() -> { refreshState.setText("API unavailable · " + e.getMessage()); status.setText(serverData.isEmpty() ? "Could not load servers" : "Showing last successful server list"); loading=false; renderServerPage(); }); }
            finally { if(connection!=null)connection.disconnect(); }
        });
    }
    private String readBody(HttpURLConnection c,int code) throws Exception { InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream(); if(in==null)return "{}"; StringBuilder body=new StringBuilder(); try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,"UTF-8"))){String line;while((line=reader.readLine())!=null)body.append(line);} return body.toString(); }
    private JSONObject getJson(String url, JSONObject post) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setRequestProperty("Accept","application/json"); if(post!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(post.toString().getBytes("UTF-8"));}} int code=c.getResponseCode(); String body=readBody(c,code);c.disconnect();JSONObject result=new JSONObject(body);if(code<200||code>=300)throw new IOException(result.optString("message","HTTP "+code));return result; }
    private void renderServerPage() {
        if(list==null)return; ensureHistoryWindow(); list.removeAllViews(); Set<String> visited=new HashSet<>(getSharedPreferences(PREFS,MODE_PRIVATE).getStringSet("visited",Collections.emptySet())); ArrayList<JSONObject> visible=new ArrayList<>(); String query=searchInput==null?"":searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        int scanned=serverData.size(), skipped=0;
        for(JSONObject s:serverData){String id=s.optString("jobId","");int players=s.optInt("players",-1),max=s.optInt("maxPlayers",0); if(id.isEmpty()||max<=0||players<0||players>=max){skipped++;continue;} if(skipAttemptedInput!=null&&skipAttemptedInput.isChecked()&&visited.contains(id)){skipped++;continue;} if(!query.isEmpty()&&!id.toLowerCase(Locale.ROOT).contains(query)){continue;}visible.add(s);}
        final String mode=sortMode; visible.sort((a,b)->compareServers(a,b,mode)); int pages=Math.max(1,(visible.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.min(page,pages);int from=Math.min(visible.size(),(page-1)*PAGE_SIZE),to=Math.min(visible.size(),from+PAGE_SIZE);
        for(int i=from;i<to;i++)addServerCard(visible.get(i),visited.contains(visible.get(i).optString("jobId")));
        previousButton.setEnabled(page>1);moreButton.setEnabled(page<pages);moreButton.setAlpha(page<pages?1f:.5f);
        int low=Integer.MAX_VALUE;for(JSONObject s:visible)low=Math.min(low,s.optInt("players",Integer.MAX_VALUE));stats.setText(visible.size()+" eligible · "+(low==Integer.MAX_VALUE?"—":low)+" lowest players · page "+page+"/"+pages);
        int daily=getSharedPreferences(PREFS,MODE_PRIVATE).getInt("dailyCount",0);metrics.setText("Daily hops: "+daily+"/50  ·  Scanned: "+scanned+"  ·  Skipped: "+skipped+"  ·  Attempts: "+getSharedPreferences(PREFS,MODE_PRIVATE).getInt("attemptCount",0));
    }
    private int compareServers(JSONObject a,JSONObject b,String mode){String field="players";boolean descending=false;switch(mode){case "Most players":descending=true;break;case "Lowest occupancy":field="occupancy";break;case "Best FPS":field="fps";descending=true;break;case "Lowest ping":field="ping";break;}double x=a.optDouble(field,Double.NaN),y=b.optDouble(field,Double.NaN);if(Double.isNaN(x))return Double.isNaN(y)?0:1;if(Double.isNaN(y))return -1;return Double.compare(x,y)*(descending?-1:1);}
    private void addServerCard(JSONObject server,boolean visited){String id=server.optString("jobId","");int players=server.optInt("players",-1),max=server.optInt("maxPlayers",0);LinearLayout card=column();card.setPadding(dp(15),dp(13),dp(15),dp(10));card.setBackground(shape(color("#121725"),color("#252D40"),16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(7),0,dp(7));card.setLayoutParams(p);card.addView(text(players+" / "+max+" players"+(visited?" · Tried":""),15,Color.WHITE,true));double occupancy=server.optDouble("occupancy",Double.NaN),ping=server.optDouble("ping",Double.NaN),fps=server.optDouble("fps",Double.NaN);String detail=(Double.isNaN(occupancy)?"Occupancy unavailable":Math.round(occupancy)+"% occupancy")+"  ·  "+(Double.isNaN(ping)?"Ping unavailable":Math.round(ping)+" ms ping")+"  ·  "+(Double.isNaN(fps)?"FPS unavailable":Math.round(fps)+" FPS");card.addView(text(detail,12,color("#9BA3B7"),false));TextView serverId=text(id,10,color("#8993A8"),false);serverId.setPadding(0,dp(5),0,dp(2));serverId.setTextIsSelectable(true);card.addView(serverId);LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button join=button("Join",true);join.setOnClickListener(v->joinServer(id));actions.addView(join,new LinearLayout.LayoutParams(0,-2,1));Button copy=button("Copy ID",false);copy.setOnClickListener(v->{android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Roblox server ID",id));status.setText("Server ID copied");});actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));card.addView(actions);list.addView(card);}
    private void updateHopStats(JSONObject hop){int count=hop.optInt("count",0),limit=hop.optInt("limit",50);getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("dailyCount",count).apply();String reset=hop.optString("nextResetAt","");String resetText=reset.length()>=16?" · resets "+reset.substring(11,16)+" UTC":"";metrics.setText("Daily hops: "+count+"/"+limit+resetText+"  ·  Attempts this quarter: "+getSharedPreferences(PREFS,MODE_PRIVATE).getInt("attemptCount",0));}
    private void loadProfileAvatar(){executor.execute(()->{try{JSONObject payload=getJson(API_BASE+"/api/profile-avatar",null);JSONObject data=payload.optJSONObject("data");String image=data==null?"":data.optString("imageUrl","");if(!image.startsWith("https://"))return;HttpURLConnection c=(HttpURLConnection)new URL(image).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(10000);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeStream(c.getInputStream());c.disconnect();if(bitmap!=null)runOnUiThread(()->profileAvatar.setImageBitmap(bitmap));}catch(Exception ignored){}});}
    private void joinServer(String id) {
        if(joinPending)return;
        if(!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")){status.setText("This server ID is invalid");return;}
        joinPending=true;status.setText("Reserving a hop…");
        executor.execute(()->{
            String token=null;
            try {
                JSONObject request=new JSONObject();request.put("placeId",PLACE_ID);request.put("serverJobId",id);
                JSONObject reservation=getJson(API_BASE+"/api/join",request);JSONObject data=reservation.optJSONObject("data");token=data==null?null:data.optString("reservationToken",null);
                if(token==null||token.isEmpty())throw new IOException("The API did not return a launch reservation");
                final String reservationToken=token;
                runOnUiThread(()->{
                    try {
                        Intent launch=new Intent(Intent.ACTION_VIEW,Uri.parse("roblox://experiences/start?placeId="+PLACE_ID+"&gameInstanceId="+Uri.encode(id)));
                        startActivity(launch);recordVisited(id);int attempts=getSharedPreferences(PREFS,MODE_PRIVATE).getInt("attemptCount",0)+1;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("attemptCount",attempts).remove("pendingJoin").apply();status.setText("Roblox launch requested · join not verified");
                        JSONObject complete=new JSONObject();complete.put("reservationToken",reservationToken);complete.put("launchRequested",true);
                        executor.execute(()->{try{JSONObject result=getJson(API_BASE+"/api/join/complete",complete);JSONObject limit=result.optJSONObject("data");if(limit!=null){JSONObject hop=limit.optJSONObject("hopLimit");runOnUiThread(()->{if(hop!=null)updateHopStats(hop);});}}catch(Exception e){runOnUiThread(()->status.setText("Launch request sent · hop counter confirmation failed"));}finally{joinPending=false;}});
                    } catch(Exception e) { status.setText("Could not open Roblox · "+e.getMessage()); JSONObject release=new JSONObject();try{release.put("reservationToken",reservationToken);release.put("launchRequested",false);}catch(Exception ignored){} JSONObject body=release;executor.execute(()->{try{getJson(API_BASE+"/api/join/complete",body);}catch(Exception ignored){}finally{joinPending=false;}}); }
                });
            } catch(Exception e) { joinPending=false;runOnUiThread(()->{status.setText("Join failed · "+e.getMessage());if(pauseFailureInput!=null&&pauseFailureInput.isChecked())serviceAction("PAUSE");}); }
        });
    }
    private void recordVisited(String id) {
        ensureHistoryWindow();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> visited = new HashSet<>(prefs.getStringSet("visited", Collections.emptySet()));
        String window=historyWindowKey();if(!window.equals(prefs.getString("historyWindow","")))visited.clear();visited.add(id);prefs.edit().putStringSet("visited",visited).putString("historyWindow",window).apply();renderHistory();
    }
    private void clearHistory() { getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("visited").remove("resetAt").putInt("attemptCount",0).putString("historyWindow",historyWindowKey()).apply(); renderHistory(); status.setText("Visited server history cleared"); }
    private void removeVisited(String id) { Set<String> visited = new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("visited", Collections.emptySet())); visited.remove(id); getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet("visited", visited).apply(); renderHistory(); }
    private void renderHistory() {
        if (historyList == null) return; historyList.removeAllViews(); SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE); long resetAt = prefs.getLong("resetAt", 0); Set<String> visited = new TreeSet<>(prefs.getStringSet("visited", Collections.emptySet()));
        String window=historyWindowKey();if(!window.equals(prefs.getString("historyWindow",""))){visited.clear();prefs.edit().remove("visited").putInt("attemptCount",0).putString("historyWindow",window).apply();}historySummary.setText(visited.size()+" visited · current local quarter-hour window ("+window.substring(11)+")");
        if (visited.isEmpty()) { historyList.addView(text("No servers in your recent history.", 13, color("#7F899D"), false)); return; }
        for (String id : visited) { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(5), dp(8), dp(5)); row.setBackground(shape(color("#121725"), color("#252D40"), 13)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(4),0,dp(4)); row.setLayoutParams(p); TextView label = text(id, 11, color("#C3CAD8"), false); label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); row.addView(label,new LinearLayout.LayoutParams(0,-2,1)); Button remove = new Button(this); remove.setText("Remove"); remove.setAllCaps(false); remove.setTextSize(12); remove.setTextColor(color("#FF9AAB")); remove.setBackgroundColor(Color.TRANSPARENT); remove.setOnClickListener(v -> removeVisited(id)); row.addView(remove); historyList.addView(row); }
    }
    private String historyWindowKey(){Calendar now=Calendar.getInstance();return String.format(Locale.US,"%04d-%02d-%02dT%02d:%02d",now.get(Calendar.YEAR),now.get(Calendar.MONTH)+1,now.get(Calendar.DAY_OF_MONTH),now.get(Calendar.HOUR_OF_DAY),(now.get(Calendar.MINUTE)/15)*15);}
    private void ensureHistoryWindow(){SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);String window=historyWindowKey();if(!window.equals(prefs.getString("historyWindow","")))prefs.edit().remove("visited").putInt("attemptCount",0).putString("historyWindow",window).apply();}
    @Override protected void onDestroy() { try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {} executor.shutdownNow(); super.onDestroy(); }
}
