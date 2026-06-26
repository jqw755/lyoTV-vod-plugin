package com.fongmi.vod.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fongmi.vod.App;
import com.fongmi.vod.bean.Config;
import com.fongmi.vod.bean.Site;
import com.fongmi.vod.db.dao.ConfigDao;
import com.fongmi.vod.db.dao.SiteDao;

@Database(entities = {Site.class, Config.class}, version = AppDatabase.VERSION)
public abstract class AppDatabase extends RoomDatabase {

    public static final int VERSION = 1;
    public static final String NAME = "vod";

    private static volatile AppDatabase instance;

    public static synchronized AppDatabase get() {
        if (instance == null) instance = create(App.get());
        return instance;
    }

    private static AppDatabase create(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, NAME)
                .fallbackToDestructiveMigration(true)
                .allowMainThreadQueries().build();
    }

    public abstract SiteDao getSiteDao();

    public abstract ConfigDao getConfigDao();
}
