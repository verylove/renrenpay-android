package wy.experiment.xposed.hook.wechat;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import wy.experiment.xposed.bean.EventBus;
import wy.experiment.xposed.bean.LocalEvent;
import wy.experiment.xposed.db.model.Logs;
import wy.experiment.xposed.db.util.LogDao;
import wy.experiment.xposed.hook.CallBackDo;
import wy.experiment.xposed.hook.HookBase;
import wy.experiment.xposed.hook.QrBean;
import wy.experiment.xposed.hook.util.LogUtils;
import wy.experiment.xposed.hook.util.PayUtils;
import wy.experiment.xposed.hook.util.ReflecUtils;
import wy.experiment.xposed.hook.util.XmlToJson;
import wy.experiment.xposed.utils.XPConstant;

public class WechatHook extends HookBase {
    private static WechatHook mHookWechat;
    private String TAG = "cxyWechat";

    public static synchronized WechatHook getInstance() {
        if (mHookWechat == null) {
            mHookWechat = new WechatHook();
        }
        return mHookWechat;
    }


    @Override
    public void hookFirst() throws Error, Exception {
        //关屏也能打码，和打码的实现
        hookQRWindows();
    }

    @Override
    public void hookCreatQr() throws Error, Exception {
        Class<?> clazz = XposedHelpers.findClass("com.tencent.mm.plugin.collect.b.s", mAppClassLoader);
        XposedHelpers.findAndHookMethod(clazz, "a", int.class, String.class, org.json.JSONObject.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param)
                    throws Throwable {
                try {
                    QrBean qrBean = new QrBean();
                    qrBean.setChannel(QrBean.WECHAT);
                    Double money = ReflecUtils.findField(param.thisObject.getClass(), double.class, 0, false)
                            .getDouble(param.thisObject);
                    String mark = (String) ReflecUtils.findField(param.thisObject.getClass(), String.class, 1, false)
                            .get(param.thisObject);
                    String payurl = (String) ReflecUtils.findField(param.thisObject.getClass(), String.class, 2, false)
                            .get(param.thisObject);
                    Log.d(TAG, "微信成功生成二维码：" + money.floatValue() + "|" + mark + "|" + payurl);
                    qrBean.setMark_sell(mark);
                    qrBean.setUrl(payurl);
                    qrBean.setMoney(PayUtils.formatMoneyToCent(money.floatValue() + ""));
                    qrBean.setChannel(QrBean.WECHAT);

                    Intent broadCastIntent = new Intent(RECV_ACTION);
                    broadCastIntent.putExtra(RECV_ACTION_DATE, qrBean.toString());
                    broadCastIntent.putExtra(RECV_ACTION_TYPE, getLocalQrActionType());
                    broadCastIntent.putExtra("type", 3);
                    broadCastIntent.putExtra(RECV_RESULT_TYPE, 1);
                    mContext.sendBroadcast(broadCastIntent);
                } catch (Error | Exception ignore) {
                    Log.d(TAG, "微信生成二维码失败！！！");
                    Intent broadCastIntent1 = new Intent(RECV_ACTION);
                    broadCastIntent1.putExtra(RECV_ACTION_DATE, "微信生成二维码失败");
                    broadCastIntent1.putExtra(RECV_ACTION_TYPE, getLocalBillActionType());
                    broadCastIntent1.putExtra(RECV_RESULT_TYPE, 2);
                    mContext.sendBroadcast(broadCastIntent1);
                }
            }
        });
    }

    @Override
    public void hookBill() throws Error, Exception {
        XposedHelpers.findAndHookMethod("com.tencent.wcdb.database.SQLiteDatabase",
                mAppClassLoader, "insert", String.class, String.class, ContentValues.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        try {
                            ContentValues contentValues = (ContentValues) param.args[2];
                            String tableName = (String) param.args[0];
                            if (TextUtils.isEmpty(tableName) || !tableName.equals("message")) {
                                return;
                            }
                            Integer type = contentValues.getAsInteger("type");
                            if (type != null && type == 318767153) {
                                JSONObject msg = XmlToJson.documentToJSONObject(contentValues.getAsString("content"))
                                        .getJSONObject("appmsg");
                                if (!msg.getString("type").equals("5")) {
                                    //首款类型type为5
                                    return;
                                }
                                QrBean qrBean = new QrBean();
                                qrBean.setChannel(QrBean.WECHAT);
                                qrBean.setMoney((int) (Float.valueOf(msg.getJSONObject("mmreader")
                                        .getJSONObject("template_detail")
                                        .getJSONObject("line_content")
                                        .getJSONObject("topline")
                                        .getJSONObject("value")
                                        .getString("word")
                                        .replace("￥", "")) * 100));

                                qrBean.setOrder_id(msg.getString("template_id"));
                                JSONArray lines = msg.getJSONObject("mmreader")
                                        .getJSONObject("template_detail")
                                        .getJSONObject("line_content")
                                        .getJSONObject("lines")
                                        .getJSONArray("line");

                                for (int i = 0; i < 2; i++) {
                                    if (lines.size() < i + 1 && lines.getJSONObject(i) == null) {
                                        break;
                                    }
                                    if (lines.getJSONObject(i)
                                            .getJSONObject("key")
                                            .getString("word").contains("付款方")) {
                                        qrBean.setMark_buy(lines.getJSONObject(i)
                                                .getJSONObject("value")
                                                .getString("word"));
                                    } else if (lines.getJSONObject(i)
                                            .getJSONObject("key")
                                            .getString("word").contains("收款方")) {
                                        qrBean.setMark_sell(lines.getJSONObject(i)
                                                .getJSONObject("value")
                                                .getString("word"));
                                    }
                                }
                                if (TextUtils.isEmpty(qrBean.getMark_sell())) {
                                    return;
                                }

                                Log.d(TAG, "微信收到支付订单：" + qrBean.getMoney() + "|" + qrBean.getMark_sell() + "|" + qrBean.getMark_buy());
                                Intent broadCastIntent = new Intent(RECV_ACTION);
                                broadCastIntent.putExtra(RECV_ACTION_DATE, qrBean.toString());
                                broadCastIntent.putExtra(RECV_ACTION_TYPE, getLocalBillActionType());
                                broadCastIntent.putExtra("type", 2);
                                broadCastIntent.putExtra(RECV_RESULT_TYPE, 1);
                                mContext.sendBroadcast(broadCastIntent);
                            }
                        } catch (Error | Exception e) {
                            Log.d(TAG, "收款信息失败 " + e);
                            Log.e(TAG, e.getStackTrace().toString());

                            Intent broadCastIntent1 = new Intent(RECV_ACTION);
                            broadCastIntent1.putExtra(RECV_ACTION_DATE, "微信收款信息失败");
                            broadCastIntent1.putExtra(RECV_ACTION_TYPE, getLocalBillActionType());
                            broadCastIntent1.putExtra(RECV_RESULT_TYPE, 2);
                            mContext.sendBroadcast(broadCastIntent1);
                        }
                    }
                });
    }

    @Override
    public void addRemoteTaskI() {
        addRemoteTask(getRemoteQrActionType(), new CallBackDo() {
            @Override
            public void callBack(Intent intent) throws Error, Exception {
                Log.d(TAG, "获取微信二维码");
                Intent intent2 = new Intent(mContext, XposedHelpers.findClass(
                        "com.tencent.mm.plugin.collect.ui.CollectCreateQRCodeUI", mContext.getClassLoader()));
                intent2.putExtra("mark", intent.getStringExtra("mark"));
                intent2.putExtra("money", intent.getStringExtra("money"));
                intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent2);
            }
        });
    }

    @Override
    public void addLocalTaskI() {
        super.addLocalTaskI();
    }

    @Override
    public String getPackPageName() {
        return "com.tencent.mm";
    }

    @Override
    public String getAppName() {
        return "微信";
    }


    private void hookQRWindows() {
        Class<?> clazz = XposedHelpers.findClass("com.tencent.mm.plugin.collect.ui.CollectCreateQRCodeUI", mAppClassLoader);
        XposedBridge.hookAllMethods(clazz, "onCreate", new XC_MethodHook() {

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    ((Activity) param.thisObject).getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
                } catch (Error | Exception ignore) {

                }
            }
        });

        XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.collect.ui.CollectCreateQRCodeUI",
                mAppClassLoader, "initView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        try {
                            Intent intent = ((Activity) param.thisObject).getIntent();
                            String mark = intent.getStringExtra("mark");
                            String money = intent.getStringExtra("money");
                            if (TextUtils.isEmpty(mark)) {
                                return;
                            }
                            Class<?> bs = XposedHelpers.findClass("com.tencent.mm.plugin.collect.b.s", mAppClassLoader);
                            Object obj = XposedHelpers.newInstance(bs, Double.valueOf(money), "1", mark);

                            XposedHelpers.callMethod(param.thisObject, "a", obj, true, true);
                        } catch (Error | Exception ignore) {
                            LogUtils.show(ignore.getMessage() + "");
                        }
                    }
                });
    }
}
