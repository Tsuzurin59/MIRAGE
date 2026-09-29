package com.numazu.dictionary;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.StatFs;

import com.github.luben.zstd.ZstdInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JapaneseDictionary {
    static final String RAW_SHA = "8b19c7d65a7d7d6df9afc58832b17b22fd349724e5d06d2acf3bb9a6c4b0ed9d";
    static final long REQUIRED_FREE = 820L * 1024 * 1024;
    private static final String PACKED_ASSET = "japanese-dictionary.db.zst";
    final File databaseFile;
    volatile String lastSearchProblem;
    private final AssetManager assets;
    private final UserData user;

    static final class Word {
        final String expression, reading, meaning;
        final long id;
        Word(long id, String expression, String reading, String meaning) {
            ParsedEntry parsed=parseEntryJson(meaning);
            this.id=id;
            this.expression=expression==null||expression.isEmpty()?parsed.expression:expression;
            this.reading=reading==null||reading.isEmpty()?parsed.reading:reading;
            this.meaning=parsed.recognized?parsed.meaning:(meaning==null?"":meaning);
        }
    }
    private static final class ParsedEntry {
        String expression="", reading="", meaning="";
        boolean recognized;
    }
    interface Progress { void update(String message, int percent); }

    JapaneseDictionary(Context context) {
        Context app=context.getApplicationContext();
        databaseFile = new File(app.getFilesDir(), "japanese-dictionary.db");
        assets = app.getAssets();
        user = new UserData(app);
    }
    boolean ready() { return databaseFile.isFile() && databaseFile.length() > 100_000_000L; }
    long availableBytes() { return new StatFs(databaseFile.getParent()).getAvailableBytes(); }

    void install(Progress progress) throws Exception {
        File staged = new File(databaseFile.getParentFile(), "japanese-dictionary.db.part");
        if(staged.exists() && !staged.delete()) throw new IllegalStateException("无法清理上次未完成的解压文件，请清理应用存储后重试。");
        long required=REQUIRED_FREE+(databaseFile.isFile()?databaseFile.length():0);
        if (availableBytes() < required) throw new IllegalStateException("可用空间不足。当前安装约需 "+((required+1024*1024-1)/(1024*1024))+" MB 可用空间，请清理存储后再试。");
        try {
            MessageDigest raw=MessageDigest.getInstance("SHA-256");
            try(InputStream packed=new BufferedInputStream(assets.open(PACKED_ASSET)); InputStream in=new ZstdInputStream(packed); FileOutputStream out=new FileOutputStream(staged)) {
                byte[] buffer=new byte[256*1024]; long done=0,lastUi=0; int n;
                while((n=in.read(buffer))!=-1) {
                    out.write(buffer,0,n); raw.update(buffer,0,n); done+=n;
                    if(done-lastUi>=4L*1024*1024){progress.update("正在从安装包展开词库… "+(done/1024/1024)+" MB",(int)Math.min(98,done*98/(650L*1024*1024)));lastUi=done;}
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
            if(staged.exists()) staged.delete();
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
            String json=pick(entryCols,"json","entry_json","data","content","entry");
            if(json!=null && entryId!=null) try(Cursor c=db.rawQuery("SELECT "+qcol(json)+" FROM entries WHERE "+qcol(entryId)+"=?",new String[]{Long.toString(id)})) {
                if(c.moveToFirst()) {
                    ParsedEntry parsed=parseEntryJson(c.getString(0));
                    if(expression.isEmpty())expression=parsed.expression;
                    if(reading.isEmpty())reading=parsed.reading;
                    if(meaning.isEmpty())meaning=parsed.meaning;
                }
            }
        } catch(Exception ignored) { }
        return new Word(id,expression,reading,meaning);
    }
    private static ParsedEntry parseEntryJson(String value) {
        ParsedEntry parsed=new ParsedEntry();
        if(value==null || !value.trim().startsWith("{"))return parsed;
        parsed.recognized=value.contains("\"senses\"")||value.contains("\"kanji\"");
        try {
            JSONObject entry=new JSONObject(value);
            JSONArray kanji=entry.optJSONArray("kanji");
            JSONArray kana=entry.optJSONArray("kana");
            parsed.expression=firstText(kanji);
            parsed.reading=firstText(kana);
            JSONArray senses=entry.optJSONArray("senses");
            if(senses==null)return parsed;
            parsed.recognized=true;
            ArrayList<String> chinese=new ArrayList<>(), english=new ArrayList<>();
            for(int i=0;i<senses.length();i++) {
                JSONObject sense=senses.optJSONObject(i);
                if(sense==null)continue;
                JSONArray glosses=sense.optJSONArray("glosses");
                if(glosses==null)continue;
                for(int j=0;j<glosses.length();j++) {
                    JSONObject gloss=glosses.optJSONObject(j);
                    if(gloss==null)continue;
                    String text=gloss.optString("text","").trim();
                    String language=gloss.optString("lang","").toLowerCase(Locale.ROOT);
                    if(isChineseLanguage(language))addGloss(chinese,text);
                    else if(language.equals("eng"))addGloss(english,text);
                }
            }
            parsed.meaning=joinGlosses(chinese.isEmpty()?english:chinese);
        } catch(Exception ignored) { parsed.recognized=true; }
        return parsed;
    }
    private static String firstText(JSONArray values) {
        if(values==null)return "";
        for(int i=0;i<values.length();i++) {
            JSONObject item=values.optJSONObject(i);
            if(item!=null) {
                String text=item.optString("text","").trim();
                if(!text.isEmpty())return text;
            }
        }
        return "";
    }
    private static boolean isChineseLanguage(String language) {
        return language.equals("zh")||language.equals("zho")||language.equals("chi")||language.equals("cmn")||language.startsWith("zh-");
    }
    private static void addGloss(List<String> values,String value) {
        if(!value.isEmpty()&&!values.contains(value)&&values.size()<12)values.add(value);
    }
    private static String joinGlosses(List<String> values) {
        StringBuilder result=new StringBuilder();
        for(String value:values) {
            if(result.length()>0)result.append("；");
            result.append(value);
        }
        return result.toString();
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
