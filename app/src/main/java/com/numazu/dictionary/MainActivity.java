package com.numazu.dictionary;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK=Color.rgb(34,43,54), MUTED=Color.rgb(112,123,137), BLUE=Color.rgb(45,102,177), BG=Color.rgb(247,248,250);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private JapaneseDictionary dictionary;
    private LinearLayout root, body;
    private EditText search;
    private ListView list;
    private TextView emptyView;
    private TextToSpeech speech;
    private boolean speechReady=false, showingFavorites=false;
    private String query="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(Color.WHITE);
        int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(android.os.Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        dictionary=new JapaneseDictionary(this);
        speech=new TextToSpeech(this,status->{speechReady=status==TextToSpeech.SUCCESS;if(speechReady)speech.setLanguage(Locale.JAPAN);});
        if(dictionary.ready()) renderLookup(); else renderWelcome();
    }

    private void renderWelcome() {
        shell("日语大词典");
        addText("完整日中词库",28,INK,true,body);
        addText("约 21.7 万条词条，另含汉字释义、词形和 JLPT 信息。完整词库已包含在安装包中，首次使用会在本机解压；之后可离线查词。",17,MUTED,false,body,12);
        LinearLayout info=card(); addText("数据版本 2026-09-02\n安装包因包含约 86 MB 压缩词库而较大。首次解压需要约 820 MB 可用空间。",15,MUTED,false,info);
        Button install=button("安装内置大词库",true); body.addView(install,top(14));
        install.setOnClickListener(v->installDictionary(install));
        Button about=button("数据来源与许可",false);body.addView(about,top(8));about.setOnClickListener(v->about());
    }

    private void renderLookup() {
        shell("日语大词典");
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        search=new EditText(this);search.setSingleLine(true);search.setTextSize(18);search.setHint("输入日文、假名或中文释义");search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(14),dp(5),dp(10),dp(5));search.setBackgroundColor(Color.WHITE);
        bar.addView(search,new LinearLayout.LayoutParams(0,dp(52),1));Button go=button("查词",true);bar.addView(go,new LinearLayout.LayoutParams(dp(76),dp(48)));body.addView(bar);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(0,dp(8),0,dp(5));Button lookup=button("查词",!showingFavorites),fav=button("收藏",showingFavorites),hist=button("历史",false),more=button("关于",false);
        tabs.addView(lookup,new LinearLayout.LayoutParams(0,dp(42),1));tabs.addView(fav,new LinearLayout.LayoutParams(0,dp(42),1));tabs.addView(hist,new LinearLayout.LayoutParams(0,dp(42),1));tabs.addView(more,new LinearLayout.LayoutParams(0,dp(42),1));body.addView(tabs);
        list=new ListView(this);list.setDividerHeight(dp(1));body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        emptyView=text("最近查过的词会显示在这里。",15,MUTED,false);emptyView.setGravity(Gravity.CENTER);body.addView(emptyView,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(emptyView);
        go.setOnClickListener(v->runSearch());search.setOnEditorActionListener((v,id,event)->{runSearch();return true;});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();if(!showingFavorites)main.removeCallbacks(delayedSearch);if(!showingFavorites){String value=query;main.postDelayed(delayedSearch=()->searchWords(value),300);}}public void afterTextChanged(Editable e){}});
        lookup.setOnClickListener(v->{showingFavorites=false;renderLookup();});fav.setOnClickListener(v->{showingFavorites=true;showSaved(true);});
        hist.setOnClickListener(v->{showingFavorites=false;showSaved(false);});more.setOnClickListener(v->about());
        if(showingFavorites)showSaved(true);else if(!query.isEmpty())search.setText(query);else renderRows(dictionary.history(),"最近查过的词会显示在这里。",false);
    }
    private Runnable delayedSearch;
    private void runSearch(){if(search!=null){query=search.getText().toString();searchWords(query);}}
    private void searchWords(String value) {
        if(value==null||value.trim().isEmpty()){renderRows(dictionary.history(),"最近查过的词会显示在这里。",false);return;}
        String q=value.trim();
        worker.execute(()->{List<JapaneseDictionary.Word> words=dictionary.search(q,60);String problem=dictionary.lastSearchProblem;main.post(()->{if(!q.equals(query.trim())||list==null||showingFavorites)return;renderRows(words,words.isEmpty()?(problem==null?"没有找到匹配词条。可以试试日文写法、假名，或中文释义。":problem):"找到 "+words.size()+" 条词条",true);});});
    }
    private void showSaved(boolean favorite){worker.execute(()->{List<JapaneseDictionary.Word> words=favorite?dictionary.favorites():dictionary.history();main.post(()->renderRows(words,favorite?"收藏的词会留在这里。":"最近查过的词会显示在这里。",false));});}
    private void renderRows(List<JapaneseDictionary.Word> words,String empty,boolean searched) {
        if(list==null)return;ArrayList<JapaneseDictionary.Word> copy=new ArrayList<>(words);emptyView.setText(empty);
        list.setAdapter(new BaseAdapter(){public int getCount(){return copy.size();}public Object getItem(int p){return copy.get(p);}public long getItemId(int p){return copy.get(p).id;}
            public View getView(int p,View recycled,android.view.ViewGroup parent){JapaneseDictionary.Word w=copy.get(p);LinearLayout row=new LinearLayout(MainActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(15),dp(11),dp(15),dp(11));row.setBackgroundColor(Color.WHITE);
                TextView word=text(w.expression,23,INK,true);row.addView(word);if(!w.reading.isEmpty())row.addView(text(w.reading,14,MUTED,false),top(2));if(!w.meaning.isEmpty())row.addView(text(trim(w.meaning,180),15,INK,false),top(4));return row;}});
        list.setOnItemClickListener((a,v,pos,id)->openWord(copy.get(pos)));
    }
    private void openWord(JapaneseDictionary.Word word) {
        dictionary.addHistory(word);
        boolean saved=dictionary.favorite(word);
        LinearLayout pane=new LinearLayout(this);pane.setOrientation(LinearLayout.VERTICAL);pane.setPadding(dp(20),dp(14),dp(20),dp(8));
        TextView head=text(word.expression,32,INK,true);pane.addView(head);if(!word.reading.isEmpty())pane.addView(text(word.reading,19,MUTED,false),top(2));
        if(word.meaning.isEmpty())pane.addView(text("该条词目没有可显示的中文释义。",16,MUTED,false),top(15));else pane.addView(text(word.meaning,17,INK,false),top(12));
        LinearLayout actions=new LinearLayout(this);Button play=button("播放发音",false),favorite=button(saved?"★ 已收藏":"☆ 收藏",true);actions.addView(play,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(favorite,new LinearLayout.LayoutParams(0,dp(48),1));pane.addView(actions,top(14));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(pane).setNegativeButton("关闭",null).create();
        play.setOnClickListener(v->{if(speechReady){speech.setLanguage(Locale.JAPAN);speech.speak(word.reading.isEmpty()?word.expression:word.reading,TextToSpeech.QUEUE_FLUSH,null,"entry-"+word.id);}else toast("系统日语语音尚未准备好。") ;});
        favorite.setOnClickListener(v->{dictionary.toggleFavorite(word);boolean next=dictionary.favorite(word);favorite.setText(next?"★ 已收藏":"☆ 收藏");toast(next?"已加入收藏":"已取消收藏");});dialog.show();
    }
    private void installDictionary(Button button) {
        button.setEnabled(false);ProgressDialog progress=new ProgressDialog(this);progress.setTitle("安装日语大词库");progress.setMessage("准备展开内置词库…");progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);progress.setMax(100);progress.setCancelable(false);progress.show();
        worker.execute(()->{try{dictionary.install((message,pct)->main.post(()->{progress.setMessage(message);progress.setProgress(pct);}));main.post(()->{progress.dismiss();toast("日语大词库已就绪");renderLookup();});}
            catch(Exception | LinkageError e){main.post(()->{progress.dismiss();button.setEnabled(true);String message=e.getMessage();if(e instanceof LinkageError)message="解压组件未能在这台手机上加载。请安装新版应用后重试。";else if(e instanceof java.io.FileNotFoundException)message="安装包中没有词库文件，请重新下载完整的新版本 APK。";new AlertDialog.Builder(this).setTitle("词库安装失败").setMessage(message==null?"请确认安装包完整，并检查剩余空间后重试。":message).setPositiveButton("重试",(d,w)->installDictionary(button)).setNegativeButton("稍后",null).show();});}});
    }
    private void about(){new AlertDialog.Builder(this).setTitle("数据来源与许可").setMessage("词库：Tomoshi Dictionary Open Data Layer，JMdict 派生日中词数据，版本 2026-09-02。\n\n包含约 21.7 万条中日词条及其他开放数据表。主要词库表采用 CC BY-SA 4.0；kanji_strokes 表采用 CC BY-SA 3.0。\n\n来源与作者：JMdict / EDRDG；中文派生层归功 Tomoshi (Y1Z)。本应用使用独立名称与界面，不代表 Tomoshi 官方产品。修改说明：将开放数据装入 Android 应用，并制作本地查询界面。衍生数据继续按相同许可提供。\n\n数据项目、完整授权及声明：github.com/tomoshi-app/tomoshi-dict-data。词库已随安装包提供，展开后保存在应用私有空间；查询与历史记录仅保存在本机。")
        .setPositiveButton("好的",null).setNeutralButton("重装词库",(d,w)->{new AlertDialog.Builder(this).setMessage("删除本地词库后，将从安装包重新解压，约需 820 MB 可用空间。收藏和历史记录会保留。确定继续？").setPositiveButton("删除",(dd,ww)->{if(dictionary.databaseFile.delete()){renderWelcome();toast("已删除词库文件");}}).setNegativeButton("取消",null).show();}).show();}

    private void shell(String title){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);setContentView(root);LinearLayout header=new LinearLayout(this);header.setPadding(dp(18),dp(12),dp(18),dp(12));header.setGravity(Gravity.CENTER_VERTICAL);header.addView(text(title,23,INK,true),new LinearLayout.LayoutParams(-1,-2));root.addView(header);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(3),dp(16),dp(10));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(15),dp(14),dp(15),dp(14));c.setBackgroundColor(Color.WHITE);body.addView(c,top(12));return c;}
    private Button button(String value,boolean primary){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?Color.WHITE:INK);b.setBackgroundColor(primary?BLUE:Color.rgb(231,236,244));return b;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private void addText(String value,int size,int color,boolean bold,LinearLayout parent){addText(value,size,color,bold,parent,0);}
    private void addText(String value,int size,int color,boolean bold,LinearLayout parent,int margin){parent.addView(text(value,size,color,bold),top(margin));}
    private LinearLayout.LayoutParams top(int d){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(d);return p;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private String trim(String s,int n){return s.length()>n?s.substring(0,n)+"…":s;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){if(speech!=null){speech.stop();speech.shutdown();}worker.shutdownNow();super.onDestroy();}
}
