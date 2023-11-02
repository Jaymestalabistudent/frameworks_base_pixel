/*
 * Copyright (C) 2023 the RisingOS Android Project
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
package com.android.systemui.volume;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import com.android.settingslib.Utils;
import android.widget.SeekBar;

import com.android.systemui.R;

public class DotLineSeekBar extends SeekBar {
    private Context mContext;
    private int mDotColor;
    private float mDotGap;
    private float mDotWidth;
    private Paint mPaint;

    public DotLineSeekBar(Context context) {
        super(context);
        init(context, null);
    }

    public DotLineSeekBar(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        init(context, attributeSet);
    }

    public DotLineSeekBar(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        init(context, attributeSet);
    }

    public DotLineSeekBar(Context context, AttributeSet attributeSet, int i, int i2) {
        super(context, attributeSet, i, i2);
        init(context, attributeSet);
    }

    private void init(Context context, AttributeSet attributeSet) {
        mContext = context;
        mPaint = new Paint();
        Resources resources = context.getResources();
        mDotColor = Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary);
        mDotWidth = resources.getDimensionPixelSize(R.dimen.dot_width);
        mDotGap = resources.getDimensionPixelSize(R.dimen.dot_gap);
    }

    @Override 
    protected void onMeasure(int i, int i2) {
        super.onMeasure(i, i2);
    }

    @Override 
    protected void onDraw(Canvas canvas) {
        mPaint.setAntiAlias(true);
        mPaint.setColor(mDotColor);
        float measuredWidth = (getMeasuredWidth() - getPaddingStart()) - getPaddingEnd();
        float measuredHeight = getMeasuredHeight();
        float f = measuredWidth - ((6 / 100.0f) * measuredWidth);
        float f2 = mDotWidth / 2.0f;
        float f3 = (measuredWidth - mDotGap) - f2;
        while (f3 > 0.0f) {
            mPaint.setColor(mDotColor);
            canvas.drawCircle(f3, measuredHeight / 2.0f, f2, mPaint);
            f3 -= mDotGap + mDotWidth;
        }
        super.onDraw(canvas);
    }
}
