/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher2;

import android.widget.ImageView;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.content.pm.PackageManager;	
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.net.Uri;
import android.os.Handler;	
import android.os.SystemClock;
import android.view.animation.TranslateAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.graphics.RectF;
import android.graphics.drawable.TransitionDrawable;
import android.util.Log;

import com.android.launcher.R;

public class DeleteZone extends ImageView implements DropTarget, DragController.DragListener {
    private static final int ORIENTATION_HORIZONTAL = 1;
    private static final int TRANSITION_DURATION = 250;
    private static final int ANIMATION_DURATION = 200;

    private final int[] mLocation = new int[2];
    
    private Launcher mLauncher;
    private boolean mTrashMode;

    private AnimationSet mInAnimation;
    private AnimationSet mOutAnimation;
    private Animation mHandleInAnimation;
    private Animation mHandleOutAnimation;

    private int mOrientation;
    private DragController mDragController;

    private final RectF mRegion = new RectF();
    private TransitionDrawable mTransition;
    private View mHandle;
    private final Paint mTrashPaint = new Paint();

    private boolean shouldUninstall=false;	
    private Handler mHandler = new Handler();	
    private boolean mUninstallTarget=false;
    String UninstallPkg = null;

    public DeleteZone(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DeleteZone(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final int srcColor = context.getResources().getColor(R.color.delete_color_filter);
        mTrashPaint.setColorFilter(new PorterDuffColorFilter(srcColor, PorterDuff.Mode.SRC_ATOP));

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DeleteZone, defStyle, 0);
        mOrientation = a.getInt(R.styleable.DeleteZone_direction, ORIENTATION_HORIZONTAL);
        a.recycle();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTransition = (TransitionDrawable) getDrawable();
    }

    public boolean acceptDrop(DragSource source, int x, int y, int xOffset, int yOffset,
            DragView dragView, Object dragInfo) {
        return true;
    }
    
    public Rect estimateDropLocation(DragSource source, int x, int y, int xOffset, int yOffset,
            DragView dragView, Object dragInfo, Rect recycle) {
        return null;
    }

    public void onDrop(DragSource source, int x, int y, int xOffset, int yOffset,
            DragView dragView, Object dragInfo) {
        final ItemInfo item = (ItemInfo) dragInfo;

        if (item.container == -1) return;

        if (item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            if (item instanceof LauncherAppWidgetInfo) {
                mLauncher.removeAppWidget((LauncherAppWidgetInfo) item);
            }
        } else {
            if (source instanceof UserFolder) {
                final UserFolder userFolder = (UserFolder) source;
                final UserFolderInfo userFolderInfo = (UserFolderInfo) userFolder.getInfo();
                // Item must be a ShortcutInfo otherwise it couldn't have been in the folder
                // in the first place.
                userFolderInfo.remove((ShortcutInfo)item);
            }
        }
        if (item instanceof UserFolderInfo) {
            final UserFolderInfo userFolderInfo = (UserFolderInfo)item;
            LauncherModel.deleteUserFolderContentsFromDatabase(mLauncher, userFolderInfo);
            mLauncher.removeFolder(userFolderInfo);
        } else if (item instanceof LauncherAppWidgetInfo) {
            final LauncherAppWidgetInfo launcherAppWidgetInfo = (LauncherAppWidgetInfo) item;
            final LauncherAppWidgetHost appWidgetHost = mLauncher.getAppWidgetHost();
            if (appWidgetHost != null) {
                final int appWidgetId = launcherAppWidgetInfo.appWidgetId;
                // Deleting an app widget ID is a void call but writes to disk before returning
                // to the caller...
                new Thread("deleteAppWidgetId") {
                    public void run() {
                        appWidgetHost.deleteAppWidgetId(launcherAppWidgetInfo.appWidgetId);
                    }
                }.start();
            }
        }
        LauncherModel.deleteItemFromDatabase(mLauncher, item);
    }

    public void onDragEnter(DragSource source, int x, int y, int xOffset, int yOffset,
            DragView dragView, Object dragInfo) {
      //Show uninstall message
      final ItemInfo item = (ItemInfo) dragInfo;
        Log.d("DeleteZone","dragEnter");
        mTransition.reverseTransition(TRANSITION_DURATION);
		dragView.setPaint(mTrashPaint);
        mUninstallTarget = true;
          mHandler.removeCallbacks(mShowUninstaller);
          mHandler.postDelayed(mShowUninstaller, 1500);
    }

    public void onDragOver(DragSource source, int x, int y, int xOffset, int yOffset,
            DragView dragView, Object dragInfo) {
    }

    public void onDragExit(DragSource source, int x, int y, int xOffset, int yOffset,
            DragView dragView, Object dragInfo) {
        mTransition.reverseTransition(TRANSITION_DURATION);
        dragView.setPaint(null);
        //not show uninstall message
        //We need to call this delayed cause onDragExit is always called just before onDragEnd :(
      mHandler.removeCallbacks(mShowUninstaller);
        if(shouldUninstall){
          mUninstallTarget = false;
          mHandler.postDelayed(mShowUninstaller, 200);
        }
    }

    public void onDragStart(DragSource source, Object info, int dragAction) {
        final ItemInfo item = (ItemInfo) info;
        if (item != null) {
            mTrashMode = true;
            createAnimations();
            final int[] location = mLocation;
            getLocationOnScreen(location);
            mRegion.set(location[0], location[1], location[0] + mRight - mLeft,
                    location[1] + mBottom - mTop);
            mDragController.setDeleteRegion(mRegion);
            mTransition.resetTransition();
            startAnimation(mInAnimation);
            mHandle.startAnimation(mHandleOutAnimation);
            setVisibility(VISIBLE);
            //ADW Store app data for uninstall if its an Application
            //ADW Thanks to irrenhaus@xda & Rogro82@xda :)
      if(item instanceof ApplicationInfo){
        final ApplicationInfo appInfo=(ApplicationInfo) item;
              if(appInfo.iconResource != null)
          UninstallPkg = appInfo.iconResource.packageName;	
        else
        {
          PackageManager mgr = DeleteZone.this.getContext().getPackageManager();
          ResolveInfo res = mgr.resolveActivity(appInfo.intent, 0);
          UninstallPkg = res.activityInfo.packageName;
        }
      }  
    }   
 }

    public void onDragEnd() {
        if (mTrashMode) {
            mTrashMode = false;
            mDragController.setDeleteRegion(null);
            startAnimation(mOutAnimation);
            mHandle.startAnimation(mHandleInAnimation);
            setVisibility(GONE);
        }
        if(shouldUninstall && UninstallPkg!=null){
      Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:"+UninstallPkg));
      DeleteZone.this.getContext().startActivity(uninstallIntent);
        }
        
    }

    private void createAnimations() {
        if (mInAnimation == null) {
            mInAnimation = new FastAnimationSet();
            final AnimationSet animationSet = mInAnimation;
            animationSet.setInterpolator(new AccelerateInterpolator());
            animationSet.addAnimation(new AlphaAnimation(0.0f, 1.0f));
            if (mOrientation == ORIENTATION_HORIZONTAL) {
                animationSet.addAnimation(new TranslateAnimation(Animation.ABSOLUTE, 0.0f,
                        Animation.ABSOLUTE, 0.0f, Animation.RELATIVE_TO_SELF, 1.0f,
                        Animation.RELATIVE_TO_SELF, 0.0f));
            } else {
                animationSet.addAnimation(new TranslateAnimation(Animation.RELATIVE_TO_SELF,
                        1.0f, Animation.RELATIVE_TO_SELF, 0.0f, Animation.ABSOLUTE, 0.0f,
                        Animation.ABSOLUTE, 0.0f));
            }
            animationSet.setDuration(ANIMATION_DURATION);
        }
        if (mHandleInAnimation == null) {
            mHandleInAnimation = new AlphaAnimation(0.0f, 1.0f);
            mHandleInAnimation.setDuration(ANIMATION_DURATION);
        }
        if (mOutAnimation == null) {
            mOutAnimation = new FastAnimationSet();
            final AnimationSet animationSet = mOutAnimation;
            animationSet.setInterpolator(new AccelerateInterpolator());
            animationSet.addAnimation(new AlphaAnimation(1.0f, 0.0f));
            if (mOrientation == ORIENTATION_HORIZONTAL) {
                animationSet.addAnimation(new FastTranslateAnimation(Animation.ABSOLUTE, 0.0f,
                        Animation.ABSOLUTE, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                        Animation.RELATIVE_TO_SELF, 1.0f));
            } else {
                animationSet.addAnimation(new FastTranslateAnimation(Animation.RELATIVE_TO_SELF,
                        0.0f, Animation.RELATIVE_TO_SELF, 1.0f, Animation.ABSOLUTE, 0.0f,
                        Animation.ABSOLUTE, 0.0f));
            }
            animationSet.setDuration(ANIMATION_DURATION);
        }
        if (mHandleOutAnimation == null) {
            mHandleOutAnimation = new AlphaAnimation(1.0f, 0.0f);
            mHandleOutAnimation.setFillAfter(true);
            mHandleOutAnimation.setDuration(ANIMATION_DURATION);
        }
    }


    //Runnable to show the uninstall message (or reset the uninstall status)
    private Runnable mShowUninstaller = new Runnable() {
    public void run() {
             shouldUninstall=mUninstallTarget;
             CharSequence msg="Drop to Uninstall";
             if(shouldUninstall){
               Toast.makeText(mContext, msg, 500).show();
             }
    }
    };

    void setLauncher(Launcher launcher) {
        mLauncher = launcher;
    }

    void setDragController(DragController dragController) {
        mDragController = dragController;
    }

    void setHandle(View view) {
        mHandle = view;
    }

    private static class FastTranslateAnimation extends TranslateAnimation {
        public FastTranslateAnimation(int fromXType, float fromXValue, int toXType, float toXValue,
                int fromYType, float fromYValue, int toYType, float toYValue) {
            super(fromXType, fromXValue, toXType, toXValue,
                    fromYType, fromYValue, toYType, toYValue);
        }

        @Override
        public boolean willChangeTransformationMatrix() {
            return true;
        }

        @Override
        public boolean willChangeBounds() {
            return false;
        }
    }

    private static class FastAnimationSet extends AnimationSet {
        FastAnimationSet() {
            super(false);
        }

        @Override
        public boolean willChangeTransformationMatrix() {
            return true;
        }

        @Override
        public boolean willChangeBounds() {
            return false;
        }
    }
}
