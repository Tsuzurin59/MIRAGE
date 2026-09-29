package com.numazu.dictionary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.StatFs;

import com.github.luben.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JapaneseDictionary {
    static final String RELEASE = "2026-09-02";
    static final String COMPRESSED_SHA = "7153dfd7a8e42e2d920308370eac90cf9f2e4b4cfe67fb9a86e9aa1c89494073";
    static final String RAW_SHA = "8b19c7d65a7d7d6df9afc58832b17b22fd349724e5d06d2acf3bb9a6c4b0ed9d";
    static final String DOWNLOAD_URL = "https://github.com/tomoshi-app/tomoshi-dict-data/releases/download/v2026-09-02/tomoshi-dict-open.db.zst";
    static final long REQUIRED_FREE = 820L * 1024 * 1024;
    final File databaseFile;
    volatile String lastSearchProblem;
    private final UserData user;

    static final class Word {
        final String expression, reading, meaning;
        final long id;
        Word(long id, String expression, String reading, String meaning) {
            this.id=id; this.expression=expression; this.reading=reading; this.meaning=meaning;
        }
    }
    interface Progress { void update(String message, int percent); }

    JapaneseDictionary(Context context) {
        databaseFile = new File(context.getFilesDir(), "japanese-dictionary.db");
        user = new UserData(context);
    }
    boolean ready() { return databaseFile.isFile() && databaseFile.length() > 100_000_000L; }
    long availableBytes() { return new StatFs(databaseFile.getParent()).getAvailableBytes(); }

    void install(Progress progress) throws Exception {
        if (availableBytes() < REQUIRED_FREE) throw new IllegalStateException("可用空间不足。首次安装需要约 820 MB 空间，请清理存储后再试。");
        File packed = new File(databaseFile.getParentFile(), "dictionary-download.zst.part");
        File staged = new File(databaseFile.getParentFile(), "japanese-dictionary.db.part");
        HttpURLConnection connection = (HttpURLConnection)new URL(DOWNLOAD_URL).openConnection();
        connection.setConnectTimeout(20_000); connection.setReadTimeout(45_000); connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NihongoDictionary/1.0");
        try {
            connection.connect();
            int code=connection.getResponseCode();
            if(code < 200 || code >= 300) throw new IllegalStateException("词库服务器返回错误："+code);
            int total=connection.getContentLength();
            MessageDigest compressed=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new BufferedInputStream(connection.getInputStream()); FileOutputStream out=new FileOutputStream(packed)) {
                byte[] buffer=new byte[128*1024]; long done=0,lastUi=0; int n;
                while((n=in.read(buffer))!=-1) {
                    out.write(buffer,0,n); compressed.update(buffer,0,n); done+=n;
                    if(done-lastUi>=2L*1024*1024 || (total>0&&done>=total)) { int pct=total>0?(int)Math.min(49,done*49/total):0;progress.update("正在下载日语大词库… "+(done/1024/1024)+" MB",pct);lastUi=done; }
                }
            }
            if(!COMPRESSED_SHA.equals(hex(compressed.digest()))) throw new SecurityException("词库下载校验失败。请检查网络后重试。");
            MessageDigest raw=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new ZstdInputStream(new BufferedInputStream(new FileInputStream(packed))); FileOutputStream out=new FileOutputStream(staged)) {
                byte[] buffer=new byte[256*1024]; long done=0,lastUi=0; int n;
                while((n=in.read(buffer))!=-1) {
                    out.write(buffer,0,n); raw.update(buffer,0,n); done+=n;
                    if(done-lastUi>=4L*1024*1024){progress.update("正在展开词库… "+(done/1024/1024)+" MB",50+(int)Math.min(48,done*48/(650L*1024*1024)));lastUi=done;}
                }
            }
            if(!RAW_SHA.equals(hex(raw.digest()))) throw new SecurityException("解压后的词库校验失败，请重新安装。");
            try(SQLiteDatabase check=SQLiteDatabase.openDatabase(staged.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
                try(Cursor c=check.rawQuery("SELECT COUNT(*) FROM entries",null)) { if(!c.moveToFirst() || c.getLong(0)<200_000) throw new IllegalStateException("词库数据不完整。"); }
            }
            if(databaseFile.exists() && !databaseFile.delete()) throw new IllegalStateException("无法替换旧词库。");
            if(!staged.renameTo(databaseFile)) throw new IllegalStateException("无法保存词库文件。");
            progress.update("词库已安装。现在可以离线查词。",100);
        } finally {
            connection.disconnect();
            if(packed.exists()) packed.delete(); if(staged.exists()) staged.delete();
        }
    }

    List<Word> search(String input, int limit) {
        lastSearchProblem=null;
        if(!ready()) return new ArrayList<>();
        String q=input.trim(); if(q.isEmpty()) return new ArrayList<>();
        ArrayList<Word> out=new ArrayList<>();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            List<String> fc=columns(db,"forms"), ec=columns(db,"entries");
            String form=pick(fc,"form","surface","expression","word","text","written_form","form_text","surface_form","spelling","term","written");
            String formId=pick(fc,"ent_seq","entry_seq","entry_id","entry_key","entry","vocab_id","id");
            String entryId=pick(ec,"ent_seq","entry_seq","entry_id","entry_key","id");
            if(form==null || formId==null) { lastSearchProblem="词库已安装，但无法识别词条索引格式。";return out; }
            String sql="SELECT f."+qcol(formId)+", f."+qcol(form)+" FROM forms f WHERE f."+qcol(form)+"=? COLLATE NOCASE ORDER BY f."+qcol(form)+" LIMIT ?";
            try(Cursor c=db.rawQuery(sql,new String[]{q, Integer.toString(limit)})) {
                while(c.moveToNext()) {
                    long id=c.getLong(0); String expr=c.getString(1);
                    if(!exists(out,id)) out.add(readWord(db,id,entryId,ec,expr));
                }
            }
            if(out.isEmpty()) {
                try(Cursor c=db.rawQuery("SELECT f."+qcol(formId)+", f."+qcol(form)+" FROM forms f WHERE f."+qcol(form)+" LIKE ? ORDER BY f."+qcol(form)+" LIMIT ?",new String[]{q+"%",Integer.toString(limit)})) {
                    while(c.moveToNext()) { long id=c.getLong(0); if(!exists(out,id)) out.add(readWord(db,id,entryId,ec,c.getString(1))); }
                }
            }
            if(out.isEmpty()) {
                List<String> zc=columns(db,"zh_defs"); String zid=pick(zc,"ent_seq","entry_seq","entry_id","entry_key","entry","vocab_id","id");
                String gloss=pick(zc,"gloss","definition","translation","text","meaning","zh","zh_text","chinese","value","definition_zh","translation_zh");
                if(zid!=null && gloss!=null) try(Cursor c=db.rawQuery("SELECT DISTINCT "+qcol(zid)+" FROM zh_defs WHERE "+qcol(gloss)+" LIKE ? LIMIT ?",new String[]{"%"+q+"%",Integer.toString(limit)})) {
                    while(c.moveToNext()) { long id=c.getLong(0); if(!exists(out,id))out.add(readWord(db,id,entryId,ec,null)); }
                }
            }
            if(out.isEmpty() && q.codePointCount(0,q.length())==1) {
                Word kanji=searchKanji(db,q);
                if(kanji!=null)out.add(kanji);
            }
        } catch(Exception e) { lastSearchProblem="词库读取出错："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()); }
        return out;
    }
    private Word readWord(SQLiteDatabase db,long id,String entryId,List<String> entryCols,String expr) {
        String reading="", meaning="", expression=expr==null?"":expr;
        try {
            List<String> fc=columns(db,"forms"); String form=pick(fc,"form","surface","expression","word","text","written_form","form_text","surface_form","spelling","term","written"); String fid=pick(fc,"ent_seq","entry_seq","entry_id","entry_key","entry","vocab_id","id");
            if(form!=null && fid!=null) try(Cursor c=db.rawQuery("SELECT "+qcol(form)+" FROM forms WHERE "+qcol(fid)+"=?",new String[]{Long.toString(id)})) {
                while(c.moveToNext()) { String x=c.getString(0); if(x!=null && !x.isEmpty() && expression.isEmpty())expression=x; if(x!=null && isKana(x)) { reading=x; break; } }
            }
            List<String> zc=columns(db,"zh_defs"); String zid=pick(zc,"ent_seq","entry_seq","entry_id","entry_key","entry","vocab_id","id");
            String gloss=pick(zc,"gloss","definition","translation","text","meaning","zh","zh_text","chinese","value","definition_zh","translation_zh");
            if(zid!=null && gloss!=null) try(Cursor c=db.rawQuery("SELECT "+qcol(gloss)+" FROM zh_defs WHERE "+qcol(zid)+"=? LIMIT 12",new String[]{Long.toString(id)})) {
                while(c.moveToNext()) { String x=c.getString(0); if(x!=null && !x.trim().isEmpty()) { if(meaning.length()>0)meaning+="；"; meaning+=x.trim(); } }
            }
            if(meaning.isEmpty()) {
                String json=pick(entryCols,"json","entry_json","data","content","entry");
                if(json!=null) try(Cursor c=db.rawQuery("SELECT "+qcol(json)+" FROM entries WHERE "+qcol(entryId)+"=?",new String[]{Long.toString(id)})) { if(c.moveToFirst())meaning=c.getString(0); }
            }
        } catch(Exception ignored) { }
        return new Word(id,expression,reading,meaning);
    }
    private Word searchKanji(SQLiteDatabase db,String character) {
        List<String> kc=columns(db,"kanji");String literal=pick(kc,"literal","character","kanji","glyph");if(literal==null)return null;
        String reading="",meaning="";long id=-((long)character.codePointAt(0));
        try(Cursor c=db.rawQuery("SELECT * FROM kanji WHERE "+qcol(literal)+"=? LIMIT 1",new String[]{character})){
            if(!c.moveToFirst())return null;Map<String,String> values=new HashMap<>();for(int i=0;i<c.getColumnCount();i++)values.put(c.getColumnName(i).toLowerCase(Locale.ROOT),c.isNull(i)?"":c.getString(i));
            String on=first(values,"on_readings","on","on_yomi","onyomi"),kun=first(values,"kun_readings","kun","kun_yomi","kunyomi");
            if(!on.isEmpty())reading="音："+on;if(!kun.isEmpty())reading+=(reading.isEmpty()?"":"  ")+"训："+kun;
            meaning=first(values,"gloss","meaning","meanings","english_meaning");
        }catch(Exception ignored){return null;}
        try{List<String> gc=columns(db,"kanji_gloss");String gid=pick(gc,"literal","character","kanji","glyph");String gloss=pick(gc,"gloss","definition","translation","text","meaning","zh","value");if(gid!=null&&gloss!=null)try(Cursor c=db.rawQuery("SELECT "+qcol(gloss)+" FROM kanji_gloss WHERE "+qcol(gid)+"=? LIMIT 10",new String[]{character})){while(c.moveToNext()){String s=c.getString(0);if(s!=null&&!s.trim().isEmpty()){if(!meaning.isEmpty())meaning+="；";meaning+=s.trim();}}}}catch(Exception ignored){}
        return new Word(id,character,reading,meaning);
    }
    private static String first(Map<String,String> values,String... keys){for(String key:keys){String v=values.get(key);if(v!=null&&!v.isEmpty())return v;}return "";}
    void addHistory(Word word) { user.add(word,false); }
    void toggleFavorite(Word word) { user.toggle(word); }
    boolean favorite(Word word) { return user.has(word.id); }
    List<Word> history() { return user.history(); }
    List<Word> favorites() { return user.list(true); }

    private static boolean exists(List<Word> list,long id) { for(Word w:list)if(w.id==id)return true;return false; }
    private static boolean isKana(String s) { if(s.isEmpty())return false; for(int i=0;i<s.length();i++){char c=s.charAt(i);if(!((c>='ぁ'&&c<='ゖ')||(c>='ァ'&&c<='ヺ')||c=='ー'||c=='・'))return false;}return true; }
    private static String qcol(String s) { return "\""+s.replace("\"","\"\"")+"\""; }
    private static String pick(List<String> cols,String... options) { for(String option:options)for(String col:cols)if(col.equalsIgnoreCase(option))return col;return null; }
    private static List<String> columns(SQLiteDatabase db,String table) {
        ArrayList<String> out=new ArrayList<>();
        try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){while(c.moveToNext())out.add(c.getString(1));}catch(Exception ignored){}
        return out;
    }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }

    private static final class UserData extends SQLiteOpenHelper {
        UserData(Context c){super(c,"user-words.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE saved (id INTEGER PRIMARY KEY, expression TEXT, reading TEXT, meaning TEXT, favorite INTEGER NOT NULL DEFAULT 0, searched_at INTEGER NOT NULL)");}
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
        synchronized void add(Word w,boolean toggle){SQLiteDatabase db=getWritableDatabase();int current=0;try(Cursor c=db.rawQuery("SELECT favorite FROM saved WHERE id=?",new String[]{Long.toString(w.id)})){if(c.moveToFirst())current=c.getInt(0);}ContentValues v=new ContentValues();v.put("id",w.id);v.put("expression",w.expression);v.put("reading",w.reading);v.put("meaning",w.meaning);v.put("searched_at",System.currentTimeMillis());v.put("favorite",toggle?1-current:current);db.insertWithOnConflict("saved",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
        synchronized void toggle(Word w){add(w,true);}
        synchronized boolean has(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT favorite FROM saved WHERE id=?",new String[]{Long.toString(id)})){return c.moveToFirst()&&c.getInt(0)==1;}}
        synchronized List<Word> history(){return read("SELECT id,expression,reading,meaning FROM saved ORDER BY searched_at DESC LIMIT 1000",null);}
        synchronized List<Word> list(boolean fav){return read("SELECT id,expression,reading,meaning FROM saved WHERE favorite=? ORDER BY searched_at DESC LIMIT 1000",new String[]{fav?"1":"0"});}
        private List<Word> read(String sql,String[] args){ArrayList<Word> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(sql,args)){while(c.moveToNext())out.add(new Word(c.getLong(0),c.getString(1),c.getString(2),c.getString(3)));}return out;}
    }
}
