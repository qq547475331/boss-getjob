package com.example.bossresume;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BossResumeService extends AccessibilityService {

    private static final String TAG = "BossResumeService";
    public static final String ACTION_START = "com.example.bossresume.ACTION_START";
    public static final String ACTION_STOP = "com.example.bossresume.ACTION_STOP";
    
    private boolean isRunning = false;
    private int totalCount = 0;
    private int maxCount = 150;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    // 添加打招呼计数器
    private int greetingCount = 0;
    private static final int MAX_GREETING_COUNT = 300; // 将最大打招呼次数设为300
    private boolean isServiceStopping = false; // 添加标记，避免重复停止
    
    // 关键词列表
    private List<String> keywords = Arrays.asList(
            "运维", "docker", "k8s", "系统运维", "集群运维", "kubernetes", "devops",
            "PaaS", "应用运维", "交付", "迁移", "K8S", "运维开发", "云计算", 
            "实施", "业务运维", "SRE", "sre", "云平台", "linux", "DevOps", 
            "公有云", "私有云", "基础架构", "容器"
    );
    
    // 已点击的节点记录，避免重复点击
    private List<String> clickedNodes = new ArrayList<>();
    
    // 当前状态
    private enum State {
        BROWSING_LIST,    // 浏览职位列表
        VIEWING_DETAIL,   // 查看职位详情
        COMMUNICATING     // 沟通/投递中
    }
    
    private State currentState = State.BROWSING_LIST;

    // 界面类型
    private enum PageType {
        MAIN_LIST,       // 主界面职位列表（有"推荐"、"附近"、"最新"、"职位"等文字）
        JOB_DETAIL,      // 职位详情页面（有"职位详情"、"立即沟通"或"继续沟通"按钮）
        CHAT_PAGE        // 聊天页面（有聊天输入框、发送按钮等）
    }

    // 添加返回操作计数器和时间戳，用于控制返回频率
    private int backOperationCount = 0;
    private long lastBackTime = 0;

    // 添加类级别的静态变量
    private static long lastStateChangeTime = 0;

    // 添加页面加载等待时间常量
    private static final int DETAIL_PAGE_LOAD_DELAY = 8000; // 职位详情页面加载等待时间
    private static final int MAIN_PAGE_LOAD_DELAY = 1000;   // 主页面加载等待时间
    private static final int CHAT_PAGE_LOAD_DELAY = 8000;   // 聊天页面加载等待时间
    private static final int BACK_OPERATION_DELAY = 1000;   // 返回操作之间的等待时间
    private static final int PAGE_TRANSITION_DELAY = 5000;  // 页面切换后的等待时间

    // 添加最大返回操作次数限制
    private static final int MAX_BACK_OPERATIONS = 10; // 单次会话最大返回操作次数
    private int totalBackOperations = 0; // 记录总返回操作次数
    private long sessionStartTime = 0; // 会话开始时间

    // 添加更严格的返回操作控制
    private static final int MAX_CONSECUTIVE_BACKS = 1; // 最大连续返回次数，降低为1避免多次返回
    private int consecutiveBackCount = 0; // 连续返回计数
    private long lastSuccessfulOperation = 0; // 上次成功操作时间（非返回操作）

    // 添加页面特定的最大返回次数限制
    private static final int MAX_BACKS_MAIN_PAGE = 0;     // 主界面最大返回次数
    private static final int MAX_BACKS_DETAIL_PAGE = 1;   // 职位详情页最大返回次数
    private static final int MAX_BACKS_CHAT_PAGE = 2;     // 聊天页面最大返回次数

    // 添加打招呼相关常量和变量
    private static final String GREETING_MESSAGE = "我叫桂晨"; // 打招呼用语
    private boolean greetingSent = false; // 标记是否已发送打招呼消息
    private boolean greetingDetected = false; // 标记是否检测到已发送的打招呼消息

    // 添加一个变量跟踪当前界面层级
    private PageType currentPageType = null;
    private PageType previousPageType = null;
    
    // 添加屏幕尺寸变量
    private int screenWidth = 0;
    private int screenHeight = 0;

    // 添加滑动状态跟踪变量
    private long lastScrollTime = 0;
    private static final int SCROLL_COOLDOWN = 2000; // 滑动冷却时间2秒

    // 添加BOSS直聘包名和启动Activity常量
    private static final String BOSS_PACKAGE_NAME = "com.hpbr.bosszhipin";
    private static final String BOSS_MAIN_ACTIVITY = "com.hpbr.bosszhipin.module.launcher.WelcomeActivity";
    private static final int APP_RESTART_DELAY = 2000; // 重启APP延迟时间
    private boolean appExitDetected = false; // 标记是否检测到APP退出
    
    // 添加返回操作间隔时间检查变量
    private long lastBackOperationTime = 0;
    private static final long MIN_BACK_INTERVAL = 3000; // 两次返回操作之间的最小间隔时间（3秒）
    
    // 添加滑动状态跟踪变量
    private boolean isScrolling = false;

    // 添加职位标签点击时间控制变量
    private long lastPositionTabClickTime = 0;
    private static final long MIN_TAB_CLICK_INTERVAL = 2000; // 最小点击间隔2秒
    private boolean isTabClickPending = false; // 标记是否有待执行的标签点击

    // 获取当前界面类型
    private PageType getCurrentPageType(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return null;
        
        // 记录所有检测到的特征，用于调试
        StringBuilder features = new StringBuilder("检测到的界面特征: ");
        int featureCount = 0;
        
        // 首先检查是否有"再按一次退出程序"提示 - 这是职位主界面的明确标志
        List<AccessibilityNodeInfo> exitPromptNodes = rootNode.findAccessibilityNodeInfosByText("再按一次退出");
        if (!exitPromptNodes.isEmpty()) {
            logMessage("检测到退出提示，判断为职位主界面");
            return PageType.MAIN_LIST;
        }
        
        // 检查是否在职位详情页 - 优先检查，因为这个判断更明确
        // 职位详情页特征：有"立即沟通"或"继续沟通"按钮
        List<AccessibilityNodeInfo> communicateNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/btn_chat");
        for (AccessibilityNodeInfo node : communicateNodes) {
            if (node.getText() != null) {
                String buttonText = node.getText().toString();
                if (buttonText.equals("立即沟通") || buttonText.equals("继续沟通")) {
                    logMessage("检测到" + buttonText + "按钮，判断为职位详情页");
                    return PageType.JOB_DETAIL;
                }
            }
        }
        
        // 通过文本查找"立即沟通"或"继续沟通"按钮
        List<AccessibilityNodeInfo> immediateNodes = rootNode.findAccessibilityNodeInfosByText("立即沟通");
        List<AccessibilityNodeInfo> continueNodes = rootNode.findAccessibilityNodeInfosByText("继续沟通");
        if (!immediateNodes.isEmpty() || !continueNodes.isEmpty()) {
            logMessage("检测到立即沟通/继续沟通按钮，判断为职位详情页");
            return PageType.JOB_DETAIL;
        }
        
        // 首先检查是否在聊天页面 - 优先级最高
        // 1. 检查是否有聊天页面特有的功能按钮
        List<AccessibilityNodeInfo> sendResumeNodes = rootNode.findAccessibilityNodeInfosByText("发简历");
        List<AccessibilityNodeInfo> changePhoneNodes = rootNode.findAccessibilityNodeInfosByText("换电话");
        List<AccessibilityNodeInfo> changeWechatNodes = rootNode.findAccessibilityNodeInfosByText("换微信");
        List<AccessibilityNodeInfo> notInterestedNodes = rootNode.findAccessibilityNodeInfosByText("不感兴趣");
        
        // 如果同时存在这些特征按钮，判断为聊天页面
        if (!sendResumeNodes.isEmpty() && !changePhoneNodes.isEmpty() && 
            !changeWechatNodes.isEmpty() && !notInterestedNodes.isEmpty()) {
            logMessage("检测到聊天页面特征按钮(发简历/换电话/换微信/不感兴趣)，判断为聊天页面");
            return PageType.CHAT_PAGE;
        }
        
        // 2. 检查是否有聊天页面特有的ID
        List<AccessibilityNodeInfo> contentTextNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/tv_content_text");
        if (!contentTextNodes.isEmpty()) {
            logMessage("检测到聊天内容文本节点，判断为聊天页面");
            return PageType.CHAT_PAGE;
        }
        
        // 3. 检查是否有输入框和发送按钮
        List<AccessibilityNodeInfo> editTextNodes = findNodesByClassName(rootNode, "android.widget.EditText");
        List<AccessibilityNodeInfo> sendButtons = rootNode.findAccessibilityNodeInfosByText("发送");
        if (!editTextNodes.isEmpty() && !sendButtons.isEmpty()) {
            logMessage("检测到输入框和发送按钮，判断为聊天页面");
            return PageType.CHAT_PAGE;
        }
        
        // 检查是否在主界面
        // 主界面特征：有"推荐"、"附近"、"最新"等标签，或者底部有"职位"标签
        List<AccessibilityNodeInfo> recommendNodes = rootNode.findAccessibilityNodeInfosByText("推荐");
        List<AccessibilityNodeInfo> nearbyNodes = rootNode.findAccessibilityNodeInfosByText("附近");
        List<AccessibilityNodeInfo> newestNodes = rootNode.findAccessibilityNodeInfosByText("最新");
        
        if (!recommendNodes.isEmpty() || !nearbyNodes.isEmpty() || !newestNodes.isEmpty()) {
            features.append("推荐/附近/最新标签 ");
            featureCount++;
        }
        
        // 检查底部导航栏中的"职位"标签是否存在且被选中
        List<AccessibilityNodeInfo> tabNodes = rootNode.findAccessibilityNodeInfosByText("职位");
        boolean positionTabSelected = false;
        for (AccessibilityNodeInfo node : tabNodes) {
            // 检查是否被选中
            if (node.isSelected()) {
                features.append("底部职位标签(已选中) ");
                featureCount++;
                positionTabSelected = true;
                break;
            } else if (node.getText() != null && node.getText().toString().equals("职位")) {
                features.append("底部职位标签 ");
                featureCount++;
                break;
            }
        }
        
        // 如果职位标签被选中，这是一个强有力的主界面特征
        if (positionTabSelected) {
            logMessage("检测到底部职位标签被选中，判断为职位主界面");
            return PageType.MAIN_LIST;
        }
        
        // 额外检查是否有职位列表
        List<AccessibilityNodeInfo> jobListNodes = findJobCards(rootNode);
        if (!jobListNodes.isEmpty()) {
            features.append("职位列表 ");
            featureCount++;
        }
        
        // 如果有主界面特征，返回MAIN_LIST
        if (featureCount > 0) {
            logMessage(features.toString() + "- 判断为主界面");
            return PageType.MAIN_LIST;
        }
        
        // 如果无法识别当前界面类型，返回null
        logMessage("未能识别当前界面类型");
        return null;
    }

    // 重启BOSS直聘APP
    private void restartBossApp() {
        logMessage("尝试重启BOSS直聘APP");
        
        // 创建启动BOSS直聘的Intent
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(BOSS_PACKAGE_NAME, BOSS_MAIN_ACTIVITY);
        
        try {
            // 启动BOSS直聘
            startActivity(intent);
            logMessage("成功启动BOSS直聘APP");
            
            // 延迟后重置状态
            handler.postDelayed(() -> {
                appExitDetected = false;
                // 延迟后开始查找职位
                handler.postDelayed(() -> {
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    if (rootNode != null) {
                        PageType currentPage = getCurrentPageType(rootNode);
                        if (currentPage == PageType.MAIN_LIST) {
                            logMessage("重启后已在主界面，开始查找职位");
                            findAndClickJobs(rootNode);
                        }
                    }
                }, MAIN_PAGE_LOAD_DELAY);
            }, APP_RESTART_DELAY);
        } catch (Exception e) {
            logMessage("启动BOSS直聘APP失败: " + e.getMessage());
        }
    }
    
    // 根据精确的图标信息查找并点击BOSS直聘图标
    private void findAndClickBossIconByInfo() {
        logMessage("尝试根据精确图标信息查找BOSS直聘图标");
        
        // 获取当前活动窗口的根节点
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            logMessage("无法获取当前窗口，尝试其他方法");
            findAndClickBossIconByGeneral();
            return;
        }
        
        // 方法1：通过resource-id和content-desc查找
        List<AccessibilityNodeInfo> iconNodes = rootNode.findAccessibilityNodeInfosByViewId("com.miui.home:id/icon_icon");
        logMessage("通过resource-id找到 " + iconNodes.size() + " 个可能的图标");
        
        for (AccessibilityNodeInfo iconNode : iconNodes) {
            CharSequence contentDesc = iconNode.getContentDescription();
            if (contentDesc != null && contentDesc.toString().contains("BOSS直聘")) {
                logMessage("找到BOSS直聘图标，通过content-desc匹配");
                clickNode(iconNode);
                
                // 延迟后检查是否成功启动
                handler.postDelayed(() -> checkBossAppStarted(), 5000);
                return;
            }
        }
        
        // 方法2：通过bounds信息点击
        logMessage("未通过content-desc找到图标，尝试通过bounds信息点击");
        // 根据提供的bounds信息 [838,110][1006,278] 计算中心点
        int centerX = (838 + 1006) / 2;
        int centerY = (110 + 278) / 2;
        
        logMessage("点击坐标: (" + centerX + ", " + centerY + ")");
        clickAtPosition(centerX, centerY);
        
        // 延迟后检查是否成功启动
        handler.postDelayed(() -> checkBossAppStarted(), 5000);
    }
    
    // 通过一般方法查找并点击BOSS直聘图标
    private void findAndClickBossIconByGeneral() {
        logMessage("尝试通过一般方法查找BOSS直聘图标");
        
        // 获取当前活动窗口的根节点
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            logMessage("无法获取当前窗口，尝试通过坐标网格点击");
            clickIconByGrid();
            return;
        }
        
        // 方法1：通过文本查找
        List<AccessibilityNodeInfo> bossTextNodes = rootNode.findAccessibilityNodeInfosByText("BOSS直聘");
        if (!bossTextNodes.isEmpty()) {
            logMessage("通过文本找到BOSS直聘图标");
            for (AccessibilityNodeInfo node : bossTextNodes) {
                if (node.isClickable()) {
                    clickNode(node);
                    handler.postDelayed(() -> checkBossAppStarted(), 5000);
                    return;
                } else {
                    // 查找可点击的父节点
                    AccessibilityNodeInfo clickableParent = findClickableParent(node);
                    if (clickableParent != null) {
                        clickNode(clickableParent);
                        handler.postDelayed(() -> checkBossAppStarted(), 5000);
                        return;
                    }
                }
            }
        }
        
        // 方法2：通过content-desc查找
        List<AccessibilityNodeInfo> allNodes = getAllNodesFromRoot(rootNode);
        for (AccessibilityNodeInfo node : allNodes) {
            CharSequence contentDesc = node.getContentDescription();
            if (contentDesc != null && contentDesc.toString().contains("BOSS直聘")) {
                logMessage("通过content-desc找到BOSS直聘图标");
                if (node.isClickable()) {
                    clickNode(node);
                } else {
                    AccessibilityNodeInfo clickableParent = findClickableParent(node);
                    if (clickableParent != null) {
                        clickNode(clickableParent);
                    }
                }
                handler.postDelayed(() -> checkBossAppStarted(), 5000);
                return;
            }
        }
        
        // 如果仍未找到，尝试通过坐标网格点击
        logMessage("未找到BOSS直聘图标，尝试通过坐标网格点击");
        clickIconByGrid();
    }
    
    // 通过坐标网格点击可能的图标位置
    private void clickIconByGrid() {
        logMessage("尝试通过坐标网格点击可能的图标位置");
        
        // 首先尝试点击已知的坐标位置
        int centerX = (838 + 1006) / 2;
        int centerY = (110 + 278) / 2;
        
        logMessage("优先点击已知坐标: (" + centerX + ", " + centerY + ")");
        clickAtPosition(centerX, centerY);
        
        // 延迟后检查是否成功启动
        handler.postDelayed(() -> {
            AccessibilityNodeInfo checkNode = getRootInActiveWindow();
            if (checkNode != null && checkNode.getPackageName() != null && 
                checkNode.getPackageName().toString().equals(BOSS_PACKAGE_NAME)) {
                logMessage("成功通过已知坐标点击启动BOSS直聘APP");
                appExitDetected = false;
                return;
            }
            
            // 如果点击已知坐标失败，尝试点击桌面上可能的位置（4x5网格）
            logMessage("点击已知坐标失败，尝试网格点击");
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 4; col++) {
                    final int finalRow = row;
                    final int finalCol = col;
                    
                    handler.postDelayed(() -> {
                        int x = screenWidth * (finalCol * 2 + 1) / 8; // 将屏幕横向分为4列
                        int y = screenHeight * (finalRow * 2 + 1) / 10; // 将屏幕纵向分为5行
                        
                        logMessage("尝试点击位置: (" + x + ", " + y + ")");
                        clickAtPosition(x, y);
                        
                        // 每次点击后检查是否启动了BOSS直聘
                        handler.postDelayed(() -> {
                            AccessibilityNodeInfo checkNode2 = getRootInActiveWindow();
                            if (checkNode2 != null && checkNode2.getPackageName() != null && 
                                checkNode2.getPackageName().toString().equals(BOSS_PACKAGE_NAME)) {
                                logMessage("成功通过网格点击启动BOSS直聘APP");
                                appExitDetected = false;
                            }
                        }, 2000);
                    }, (row * 4 + col) * 2500); // 每个位置间隔2.5秒
                }
            }
        }, 3000);
    }
    
    // 检查BOSS直聘APP是否成功启动
    private void checkBossAppStarted() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null && rootNode.getPackageName() != null && 
            rootNode.getPackageName().toString().equals(BOSS_PACKAGE_NAME)) {
            logMessage("成功重启BOSS直聘APP");
            appExitDetected = false;
            
            // 延迟后检查界面类型并处理
            handler.postDelayed(() -> {
                AccessibilityNodeInfo newRootNode = getRootInActiveWindow();
                if (newRootNode != null) {
                    PageType pageType = getCurrentPageType(newRootNode);
                    if (pageType != null) {
                        handlePageByType(pageType, newRootNode);
                    }
                }
            }, MAIN_PAGE_LOAD_DELAY);
        } else {
            logMessage("BOSS直聘APP启动失败，继续尝试");
            // 尝试其他方法
            findAndClickBossIconByGeneral();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning) return;
        
        // 获取事件类型
        int eventType = event.getEventType();
        
        // 特别关注窗口状态变化事件，可能表示进入了聊天页面
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                // 优先检查是否在聊天页面
                if (checkIfInChatPage(rootNode)) {
                    // 已在checkIfInChatPage方法中处理了聊天页面
                    return;
                }
                
                // 检查是否在职位主界面
                if (isInMainPage(rootNode)) {
                    logMessage("检测到当前在职位主界面，开始查找职位");
                    handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    return;
                }
            }
        }
        
        // 对于所有事件类型，都优先检查是否在聊天页面
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || 
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                // 优先检查是否在聊天页面
                if (checkIfInChatPage(rootNode)) {
                    // 已在checkIfInChatPage方法中处理了聊天页面
                    return;
                }
            }
        }
        
        // 特别处理点击事件，可能是点击了沟通按钮
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // 延迟检查是否进入聊天页面
            handler.postDelayed(() -> {
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    // 强制检查是否进入聊天页面
                    checkIfInChatPage(rootNode);
                }
            }, 2000);
        }
        
        // 处理窗口状态变化事件
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // 获取当前活动窗口的根节点
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;
            
            // 获取当前页面类型
            PageType currentPage = getCurrentPageType(rootNode);
            
            // 特殊处理聊天页面 - 优先级最高，立即处理
            if (currentPage == PageType.CHAT_PAGE) {
                logMessage("检测到聊天页面，立即执行返回操作");
                // 增加打招呼计数
                greetingCount++;
                logMessage("当前已打招呼次数: " + greetingCount + "/" + MAX_GREETING_COUNT);
                
                // 检查是否达到最大打招呼次数
                if (greetingCount >= MAX_GREETING_COUNT) {
                    logMessage("已达到最大打招呼次数 " + MAX_GREETING_COUNT + "，准备停止服务");
                    handler.postDelayed(() -> {
                        stopService();
                    }, 3000);
                    return;
                }
                
                // 立即执行返回操作，不再延迟
                performDoubleBackToMainPage();
                return;
            }
            
            // 根据当前页面类型和状态执行相应操作
            if (currentPage != null) {
                handlePageByType(currentPage, rootNode);
            }
        }
    }

    // 优化checkIfInChatPage方法，确保能准确识别聊天页面
    private boolean checkIfInChatPage(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return false;
        
        // 记录检测到的聊天页面特征
        StringBuilder chatFeatures = new StringBuilder("检测到的聊天页面特征: ");
        int featureCount = 0;
        
        // 检查是否有聊天页面特有的功能按钮
        List<AccessibilityNodeInfo> sendResumeNodes = rootNode.findAccessibilityNodeInfosByText("发简历");
        List<AccessibilityNodeInfo> changePhoneNodes = rootNode.findAccessibilityNodeInfosByText("换电话");
        List<AccessibilityNodeInfo> changeWechatNodes = rootNode.findAccessibilityNodeInfosByText("换微信");
        List<AccessibilityNodeInfo> notInterestedNodes = rootNode.findAccessibilityNodeInfosByText("不感兴趣");
        
        // 记录检测到的特征
        if (!sendResumeNodes.isEmpty()) {
            chatFeatures.append("发简历按钮 ");
            featureCount++;
        }
        if (!changePhoneNodes.isEmpty()) {
            chatFeatures.append("换电话按钮 ");
            featureCount++;
        }
        if (!changeWechatNodes.isEmpty()) {
            chatFeatures.append("换微信按钮 ");
            featureCount++;
        }
        if (!notInterestedNodes.isEmpty()) {
            chatFeatures.append("不感兴趣按钮 ");
            featureCount++;
        }
        
        // 如果检测到至少2个特征，记录日志
        if (featureCount >= 2) {
            logMessage(chatFeatures.toString());
        }
        
        // 如果同时存在这些特征按钮，判断为聊天页面
        if (!sendResumeNodes.isEmpty() && !changePhoneNodes.isEmpty() && 
            !changeWechatNodes.isEmpty() && !notInterestedNodes.isEmpty()) {
            logMessage("检测到聊天页面特征按钮(发简历/换电话/换微信/不感兴趣)，判断为聊天页面");
            
            // 立即处理聊天页面
            handleChatPageDetected();
            return true;
        }
        
        // 如果检测到至少2个特征，也判断为聊天页面
        if (featureCount >= 2) {
            logMessage("检测到至少" + featureCount + "个聊天页面特征按钮，判断为聊天页面");
            handleChatPageDetected();
            return true;
        }
        
        // 检查是否有聊天页面特有的ID
        List<AccessibilityNodeInfo> contentTextNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/tv_content_text");
        if (!contentTextNodes.isEmpty()) {
            logMessage("检测到聊天内容文本节点，判断为聊天页面");
            
            // 立即处理聊天页面
            handleChatPageDetected();
            return true;
        }
        
        // 检查是否有输入框和发送按钮
        List<AccessibilityNodeInfo> editTextNodes = findNodesByClassName(rootNode, "android.widget.EditText");
        List<AccessibilityNodeInfo> sendButtons = rootNode.findAccessibilityNodeInfosByText("发送");
        if (!editTextNodes.isEmpty() && !sendButtons.isEmpty()) {
            logMessage("检测到输入框和发送按钮，判断为聊天页面");
            
            // 立即处理聊天页面
            handleChatPageDetected();
            return true;
        }
        
        return false;
    }

    // 修改handleChatPageDetected方法
    private void handleChatPageDetected() {
        // 添加标记，避免短时间内重复处理
        if (System.currentTimeMillis() - lastChatPageHandleTime < 5000) {
            logMessage("短时间内已处理过聊天页面，跳过此次处理");
            return;
        }
        
        // 更新最后处理时间
        lastChatPageHandleTime = System.currentTimeMillis();
        
        logMessage("检测到聊天页面，立即执行返回操作");
        
        // 检查是否达到最大打招呼次数
        if (greetingCount >= MAX_GREETING_COUNT && !isServiceStopping) {
            logMessage("【重要】已达到最大打招呼次数 " + MAX_GREETING_COUNT + "，准备停止服务");
                isServiceStopping = true;
                handler.postDelayed(() -> {
                    stopService();
            }, 2000);
                return;
        }
        
        // 立即执行返回操作
        performDoubleBackToMainPage();
    }
    
    // 添加类变量
    private long lastChatPageHandleTime = 0;
    private boolean periodicCheckRunning = false;
    
    // 添加启动定时检查的方法
    private void startPeriodicCheck() {
        if (periodicCheckRunning) return;
        
        periodicCheckRunning = true;
        logMessage("启动定时检查机制");
        
        // 每3秒检查一次当前页面
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    periodicCheckRunning = false;
                    return;
                }
                
                // 检查当前页面
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    // 优先检查是否在聊天页面
                    checkIfInChatPage(rootNode);
                }
                
                // 继续下一次检查
                handler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void findAndClickJobs(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return;
        
        // 检查是否正在滑动或滑动冷却期内
        if (isScrolling || (System.currentTimeMillis() - lastScrollTime < SCROLL_COOLDOWN)) {
            logMessage("正在滑动中或滑动冷却期内，跳过此次操作");
            return;
        }
        
        // 获取当前页面类型
        PageType currentPage = getCurrentPageType(rootNode);
        
        // 如果不在主界面，尝试返回主界面
        if (currentPage != null && currentPage != PageType.MAIN_LIST) {
            logMessage("当前不在主界面，尝试返回主界面");
            performDoubleBackToMainPage();
            return;
        }
        
        // 查找符合条件的职位节点
        List<AccessibilityNodeInfo> jobNodes = findJobNodes(rootNode);
        
        // 如果找到符合条件的职位节点，点击第一个
        if (!jobNodes.isEmpty()) {
            AccessibilityNodeInfo jobNode = jobNodes.get(0);
            String jobNodeText = getNodeText(jobNode);
            
            // 检查是否已点击过该节点
            if (clickedNodes.contains(jobNodeText)) {
                logMessage("该职位已点击过，尝试滑动查找新职位");
                scrollDown();
                return;
            }
            
            // 记录已点击的节点
            clickedNodes.add(jobNodeText);
            
            // 点击职位节点
            logMessage("点击职位: " + jobNodeText);
            clickNode(jobNode);
            
            // 更新状态
            currentState = State.VIEWING_DETAIL;
            
            // 增加总计数
            totalCount++;
            logMessage("当前已处理职位数: " + totalCount + "/" + maxCount);
            
            // 检查是否达到最大处理数量
            if (totalCount >= maxCount) {
                logMessage("已达到最大处理数量，停止服务");
                stopService();
                return;
            }
            
            // 不再需要延迟检查，直接等待页面变化事件
            return;
        }
        
        // 如果没有找到符合条件的职位节点，滑动查找更多职位
        logMessage("未找到符合条件的职位，滑动查找更多");
        scrollDown();
    }
    
    // 查找职位卡片
    private List<AccessibilityNodeInfo> findJobCards(AccessibilityNodeInfo rootNode) {
        List<AccessibilityNodeInfo> jobCards = new ArrayList<>();
        if (rootNode == null) return jobCards;
        
        // 尝试通过ID查找职位卡片
        List<AccessibilityNodeInfo> idCards = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/job_card");
        if (!idCards.isEmpty()) {
            jobCards.addAll(idCards);
            return jobCards;
        }
        
        // 如果通过ID找不到，尝试查找可能的职位卡片容器
        List<AccessibilityNodeInfo> recyclerViews = findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView");
        if (!recyclerViews.isEmpty()) {
            // 遍历RecyclerView中的子项
            for (AccessibilityNodeInfo recyclerView : recyclerViews) {
                for (int i = 0; i < recyclerView.getChildCount(); i++) {
                    AccessibilityNodeInfo child = recyclerView.getChild(i);
                    if (child != null) {
                        jobCards.add(child);
                    }
                }
            }
        }
        
        // 如果仍找不到，尝试查找所有可能的列表项
        if (jobCards.isEmpty()) {
            List<AccessibilityNodeInfo> listItems = findNodesByClassName(rootNode, "android.widget.LinearLayout");
            for (AccessibilityNodeInfo item : listItems) {
                // 检查是否可能是职位卡片（包含职位名称、公司名称等信息）
                List<String> texts = getAllTextsFromNode(item);
                if (texts.size() >= 3) { // 假设职位卡片至少包含3个文本元素
                    jobCards.add(item);
                }
            }
        }
        
        return jobCards;
    }
    
    // 根据类名查找节点
    private List<AccessibilityNodeInfo> findNodesByClassName(AccessibilityNodeInfo rootNode, String className) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (rootNode == null) return result;
        
        // 检查当前节点
        if (rootNode.getClassName() != null && rootNode.getClassName().toString().equals(className)) {
            result.add(rootNode);
        }
        
        // 递归检查子节点
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            AccessibilityNodeInfo child = rootNode.getChild(i);
            if (child != null) {
                result.addAll(findNodesByClassName(child, className));
            }
        }
        
        return result;
    }
    
    // 检查节点是否包含多个文本节点（职位卡片的特征）
    private boolean containsMultipleTextNodes(AccessibilityNodeInfo node) {
        int textNodeCount = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && child.getText() != null) {
                textNodeCount++;
                if (textNodeCount >= 2) {
                    return true;
                }
            }
        }
        return false;
    }
    
    // 获取节点中的所有文本
    private List<String> getAllTextsFromNode(AccessibilityNodeInfo node) {
        List<String> texts = new ArrayList<>();
        if (node == null) return texts;
        
        if (node.getText() != null) {
            texts.add(node.getText().toString());
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                texts.addAll(getAllTextsFromNode(child));
            }
        }
        
        return texts;
    }
    
    private String getNodeIdentifier(AccessibilityNodeInfo node) {
        if (node == null) return "";
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String text = node.getText() != null ? node.getText().toString() : "";
        
        return text + "_" + bounds.toString();
    }
    
    private void clickNode(AccessibilityNodeInfo node) {
        if (node == null) return;
        
        // 尝试直接点击
        if (node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return;
        }
        
        // 如果节点不可点击，尝试查找可点击的父节点
        AccessibilityNodeInfo parent = node;
        while (parent != null) {
            if (parent.isClickable()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return;
            }
            parent = parent.getParent();
        }
        
        // 如果没有可点击的父节点，使用手势点击
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        clickAtPosition(bounds.centerX(), bounds.centerY());
    }
    
    private void scrollScreen() {
        logMessage("执行屏幕滑动");
        isScrolling = true;
        lastScrollTime = System.currentTimeMillis();
        
        // 创建滑动路径
        Path path = new Path();
        path.moveTo(screenWidth / 2, screenHeight * 0.8f);
        path.lineTo(screenWidth / 2, screenHeight * 0.2f);
        
        // 创建手势描述
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
        
        // 执行手势
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                logMessage("滑动手势完成");
                // 延迟后重置滑动状态
                handler.postDelayed(() -> {
                    isScrolling = false;
                }, 1000);
            }
            
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                logMessage("滑动手势被取消");
                isScrolling = false;
            }
        }, null);
    }
    
    private void clickAtPosition(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            if (ACTION_START.equals(intent.getAction())) {
                startService();
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stopService();
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void startService() {
        if (!isRunning) {
            isRunning = true;
            logMessage("服务已启动");
            
            // 重置计数器和状态
            totalCount = 0;
            greetingCount = 0;
            clickedNodes.clear();
            currentState = State.BROWSING_LIST;
            
            // 重置会话相关变量
            sessionStartTime = System.currentTimeMillis();
            totalBackOperations = 0;
            consecutiveBackCount = 0;
            lastSuccessfulOperation = System.currentTimeMillis();
            
            // 启动BOSS直聘APP
            launchBossApp(this);
            logMessage("服务启动后自动启动BOSS直聘APP");
            
            // 延迟5秒后开始查找职位，给APP启动留出时间
            handler.postDelayed(() -> {
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    PageType currentPage = getCurrentPageType(rootNode);
                    if (currentPage != null) {
                        handlePageByType(currentPage, rootNode);
                    } else {
                        // 如果无法确定页面类型，尝试返回主界面
                        logMessage("无法确定当前页面类型，尝试返回主界面");
                        forceReturnToMainPage();
                    }
                }
            }, 5000);
        }
    }

    private void stopService() {
        // 只有在达到最大打招呼次数时才停止服务
        if (greetingCount < MAX_GREETING_COUNT && !isServiceStopping) {
            logMessage("未达到最大打招呼次数(" + greetingCount + "/" + MAX_GREETING_COUNT + ")，继续运行");
            // 此处保留原有代码
            return;
        }
        
        if (isServiceStopping) {
            logMessage("服务正在停止中，跳过重复停止");
            return;
        }
        
        isServiceStopping = true;
        logMessage("自动投递服务已停止，共投递 " + totalCount + " 个岗位，打招呼 " + greetingCount + " 次");
        
        // 发送广播通知MainActivity更新UI
        Intent intent = new Intent("com.example.bossresume.ACTION_SERVICE_STATUS_CHANGED");
        intent.putExtra("running", false);
        intent.putExtra("count", totalCount);
        sendBroadcast(intent);
        
        // 真正设置服务状态为停止
        isRunning = false;
    }

    private void logMessage(String message) {
        Log.d(TAG, message);
        handler.post(() -> {
            try {
                MainActivity.appendLog(getApplicationContext(), message);
            } catch (Exception e) {
                Log.e(TAG, "Error updating log: " + e.getMessage());
            }
        });
    }

    @Override
    public void onInterrupt() {
        logMessage("服务被中断，尝试恢复");
        // 不立即将isRunning设置为false，给恢复机会
        
        // 延迟检查是否需要恢复服务
        handler.postDelayed(() -> {
            if (greetingCount < MAX_GREETING_COUNT && !isServiceStopping) {
                logMessage("服务中断后尝试恢复运行");
                isRunning = true;
                
                // 尝试恢复操作
                try {
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    if (rootNode != null) {
                        PageType currentPage = getCurrentPageType(rootNode);
                        if (currentPage != null) {
                            handlePageByType(currentPage, rootNode);
                        } else {
                            // 尝试重启APP
                            restartBossApp();
                        }
                    } else {
                        // 尝试重启APP
                        restartBossApp();
                    }
                } catch (Exception e) {
                    logMessage("恢复服务时发生异常: " + e.getMessage());
                    // 失败时尝试重启APP
                    restartBossApp();
                }
            } else {
                logMessage("服务被中断，且已达到最大打招呼次数或服务正在停止中，不再恢复");
                isRunning = false;
            }
        }, 5000);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        logMessage("无障碍服务已连接");
        
        // 获取屏幕尺寸
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        
        // 启动定时检查机制
        startPeriodicCheck();
        
        // 添加服务看门狗，确保服务持续运行
        startServiceWatchdog();
    }
    
    // 添加服务看门狗机制
    private void startServiceWatchdog() {
        if (serviceWatchdogRunnable == null) {
            serviceWatchdogRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isRunning) {
                        logMessage("检测到服务已停止，尝试重启服务");
                        isRunning = true;
                        isServiceStopping = false;
                        
                        // 确保没有超过最大打招呼次数
                        if (greetingCount < MAX_GREETING_COUNT) {
                            // 尝试恢复操作
                            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                            if (rootNode != null) {
                                PageType currentPage = getCurrentPageType(rootNode);
                                if (currentPage != null) {
                                    handlePageByType(currentPage, rootNode);
                                } else {
                                    // 尝试重启APP
                                    restartBossApp();
                                }
                            } else {
                                // 尝试重启APP
                                restartBossApp();
                            }
                        }
                    }
                    
                    // 继续下一次检查
                    serviceWatchdogHandler.postDelayed(this, SERVICE_WATCHDOG_INTERVAL);
                }
            };
            
            // 启动看门狗
            serviceWatchdogHandler.postDelayed(serviceWatchdogRunnable, SERVICE_WATCHDOG_INTERVAL);
            logMessage("服务看门狗机制已启动");
        }
    }

    private void clickCommunicateButton() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        
        // 获取当前页面类型
        PageType currentPage = getCurrentPageType(rootNode);
        if (currentPage != PageType.JOB_DETAIL) {
            logMessage("当前不在职位详情页，取消点击沟通按钮");
            return;
        }
        
        // 使用新方法查找并点击底部沟通按钮
        findAndClickBottomButton(rootNode);
    }



    // 修改safeBackOperation方法，在主界面严格限制返回操作
    private void safeBackOperation() {
        // 检查是否在BOSS直聘应用内
        if (!isInBossApp()) {
            logMessage("警告：检测到已不在BOSS直聘应用内，取消返回操作");
            return;
        }
        
        // 根据当前页面类型检查返回次数限制
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            PageType currentPage = getCurrentPageType(rootNode);
            if (currentPage != null) {
                int maxAllowedBacks = MAX_CONSECUTIVE_BACKS; // 默认值
                
                // 根据页面类型设置最大允许返回次数
                switch (currentPage) {
                    case MAIN_LIST:
                        // 在主界面严格禁止返回操作
                        logMessage("当前在职位主界面，禁止执行返回操作");
                        // 检查是否有"再按一次退出程序"提示
                        List<AccessibilityNodeInfo> exitPromptNodes = rootNode.findAccessibilityNodeInfosByText("再按一次退出");
                        if (!exitPromptNodes.isEmpty()) {
                            logMessage("检测到退出提示，已在职位主界面，禁止执行返回操作，直接开始查找职位");
                            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                        } else {
                            // 如果没有退出提示，继续查找职位
                            findAndClickJobs(rootNode);
                        }
                        return; // 直接返回，不执行后续返回操作
                    case JOB_DETAIL:
                        maxAllowedBacks = MAX_BACKS_DETAIL_PAGE;
                        break;
                    case CHAT_PAGE:
                        maxAllowedBacks = MAX_BACKS_CHAT_PAGE;
                        break;
                }
                
                // 检查是否超过当前页面允许的最大返回次数
                if (consecutiveBackCount >= maxAllowedBacks) {
                    logMessage("警告：当前页面(" + currentPage + ")返回次数已达上限(" + maxAllowedBacks + ")，取消返回操作");
                    return;
                }
            }
        }
        
        // 检查连续返回次数
        if (consecutiveBackCount >= MAX_CONSECUTIVE_BACKS) {
            logMessage("警告：检测到连续返回次数过多，暂停返回操作");
            // 强制等待一段时间后再允许返回
            handler.postDelayed(() -> {
                // 重置连续返回计数
                consecutiveBackCount = 0;
                logMessage("重置连续返回计数，现在可以继续操作");
            }, 5000); // 等待5秒
            return;
        }
        
        // 检查距离上次成功操作的时间
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSuccessfulOperation > 30000) { // 如果30秒内没有成功操作
            logMessage("警告：长时间没有成功操作，可能卡在某个界面，停止服务");
            stopService();
            return;
        }
        
        // 检查总返回操作次数
        if (totalBackOperations >= MAX_BACK_OPERATIONS) {
            long sessionDuration = System.currentTimeMillis() - sessionStartTime;
            if (sessionDuration < 60000) { // 如果在1分钟内执行了过多返回操作
                logMessage("警告：短时间内返回操作过多，暂停服务");
                stopService();
                return;
            } else {
                // 重置计数器和会话时间
                totalBackOperations = 0;
                sessionStartTime = System.currentTimeMillis();
            }
        }
        
        // 检查短时间内返回次数
        if (currentTime - lastBackTime < 1000) { // 1秒内
            backOperationCount++;
            if (backOperationCount > 1) { // 1秒内超过1次返回
                logMessage("警告：短时间内返回操作过多，暂停2秒");
                backOperationCount = 0; // 重置计数器
                handler.postDelayed(this::safeBackOperation, 2000); // 延迟2秒后再尝试
                return;
            }
        } else {
            // 重置计数器
            backOperationCount = 0;
        }
        
        // 更新最后返回时间
        lastBackTime = currentTime;
        totalBackOperations++; // 增加总返回操作计数
        consecutiveBackCount++; // 增加连续返回计数
        
        // 执行返回操作
        performGlobalAction(GLOBAL_ACTION_BACK);
        logMessage("执行返回操作 (总计: " + totalBackOperations + ", 连续: " + consecutiveBackCount + ")");
    }

    // 修改isInBossApp方法，增强检测能力
    private boolean isInBossApp() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return false;
        
        CharSequence packageName = rootNode.getPackageName();
        boolean inBossApp = packageName != null && packageName.toString().contains("com.hpbr.bosszhipin");
        
        if (!inBossApp) {
            logMessage("检测到已离开BOSS直聘应用");
        }
        
        return inBossApp;
    }

    // 添加一个方法来重置连续返回计数
    private void resetConsecutiveBackCount() {
        consecutiveBackCount = 0;
        lastSuccessfulOperation = System.currentTimeMillis();
        logMessage("重置连续返回计数");
    }

    // 为节点生成唯一标识符
    private String generateNodeId(AccessibilityNodeInfo node) {
        if (node == null) return "";
        
        // 获取节点的边界信息
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        
        // 获取节点的文本内容
        String text = "";
        if (node.getText() != null) {
            text = node.getText().toString();
        }
        
        // 获取节点的描述内容
        String description = "";
        if (node.getContentDescription() != null) {
            description = node.getContentDescription().toString();
        }
        
        // 组合信息生成唯一标识符
        return text + "_" + description + "_" + bounds.toString();
    }

    // 修改performSingleBackAndCheck方法，增加页面类型检查
    private void performSingleBackAndCheck(Runnable onMainPageFound, Runnable onOtherPageFound) {
        // 先检查当前页面类型
        AccessibilityNodeInfo currentRootNode = getRootInActiveWindow();
        if (currentRootNode != null) {
            PageType currentPage = getCurrentPageType(currentRootNode);
            
            // 如果当前已经在主界面，不执行返回操作
            if (currentPage == PageType.MAIN_LIST) {
                logMessage("当前已在主界面，无需返回");
                currentState = State.BROWSING_LIST;
                resetConsecutiveBackCount();
                
                // 执行主界面回调
                if (onMainPageFound != null) {
                    onMainPageFound.run();
                }
                return;
            }
        }
        
        // 先执行一次返回
        safeBackOperation();
        
        // 等待页面加载
        handler.postDelayed(() -> {
            // 检查当前页面
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) {
                logMessage("警告：返回后无法获取页面信息，可能已退出应用");
                stopService();
                return;
            }
            
            PageType currentPage = getCurrentPageType(rootNode);
            logMessage("返回后检测到页面类型: " + (currentPage != null ? currentPage.toString() : "未知"));
            
            // 如果已经回到主界面，不再执行额外的返回操作
            if (currentPage == PageType.MAIN_LIST) {
                logMessage("已返回到主界面，不再执行额外返回");
                currentState = State.BROWSING_LIST;
                resetConsecutiveBackCount();
                
                // 执行主界面回调
                if (onMainPageFound != null) {
                    onMainPageFound.run();
                }
            } else {
                // 执行其他页面回调
                if (onOtherPageFound != null) {
                    onOtherPageFound.run();
                }
            }
        }, 1500); // 等待1.5秒检查页面
    }

    // 修改检查是否已发送打招呼消息的方法
    private boolean checkIfGreetingSent(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return false;
        
        logMessage("开始检查是否已发送打招呼消息");
        
        // 检查是否有包含打招呼内容的消息
        List<AccessibilityNodeInfo> messageNodes = findNodesByClassName(rootNode, "android.widget.TextView");
        logMessage("找到 " + messageNodes.size() + " 个文本节点");
        
        for (AccessibilityNodeInfo node : messageNodes) {
            if (node.getText() != null) {
                String messageText = node.getText().toString();
                // 记录所有消息文本，帮助调试
                if (messageText.length() > 0) {
                    logMessage("消息文本: " + messageText);
                }
                
                // 检查消息是否包含打招呼内容
                if (messageText.contains(GREETING_MESSAGE)) {
                    logMessage("检测到已发送的打招呼消息");
                    return true;
                }
                
                // 检查是否包含其他可能的打招呼内容
                if (messageText.contains("我是") || messageText.contains("桂晨") || 
                    messageText.contains("您好") || messageText.contains("你好")) {
                    logMessage("检测到可能的打招呼消息: " + messageText);
                    return true;
                }
            }
        }
        
        // 尝试通过其他方式查找打招呼消息
        List<AccessibilityNodeInfo> allNodes = getAllNodesFromRoot(rootNode);
        for (AccessibilityNodeInfo node : allNodes) {
            if (node.getText() != null) {
                String text = node.getText().toString();
                if (text.contains(GREETING_MESSAGE) || text.contains("我是") || 
                    text.contains("桂晨") || text.contains("您好") || text.contains("你好")) {
                    logMessage("通过全节点搜索检测到可能的打招呼消息: " + text);
                    return true;
                }
            }
        }
        
        logMessage("未检测到已发送的打招呼消息");
        return false;
    }
    
    // 修改performDoubleBackToMainPage方法，修复变量重复定义错误
    private void performDoubleBackToMainPage() {
        logMessage("执行双重返回到主界面操作");
        
        // 先检查当前是否已经在职位主界面
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            // 首先检查是否有"再按一次退出程序"提示
            List<AccessibilityNodeInfo> exitPromptNodes = rootNode.findAccessibilityNodeInfosByText("再按一次退出");
            if (!exitPromptNodes.isEmpty()) {
                logMessage("检测到退出提示，已在职位主界面，不执行返回操作，直接查找职位");
                handler.postDelayed(() -> {
                    AccessibilityNodeInfo finalRootNode = getRootInActiveWindow();
                    if (finalRootNode != null) {
                        findAndClickJobs(finalRootNode);
                    }
                }, MAIN_PAGE_LOAD_DELAY);
                return;
            }
            
            // 检查是否有职位标签且被选中
            List<AccessibilityNodeInfo> tabNodes = rootNode.findAccessibilityNodeInfosByText("职位");
            for (AccessibilityNodeInfo node : tabNodes) {
                if (node.isSelected()) {
                    logMessage("检测到当前已在职位主界面(职位标签已选中)，不执行返回操作，直接查找职位");
                    handler.postDelayed(() -> {
                        AccessibilityNodeInfo finalRootNode = getRootInActiveWindow();
                        if (finalRootNode != null) {
                            findAndClickJobs(finalRootNode);
                        }
                    }, MAIN_PAGE_LOAD_DELAY);
                    return;
                }
            }
        }
        
        // 直接执行两次返回操作，不再检查页面类型
        logMessage("执行第一次返回操作");
        performGlobalAction(GLOBAL_ACTION_BACK);
        
        // 延迟后执行第二次返回
        handler.postDelayed(() -> {
            // 再次检查是否已经回到职位主界面
            AccessibilityNodeInfo checkNode = getRootInActiveWindow();
            if (checkNode != null) {
                // 首先检查是否有"再按一次退出程序"提示
                List<AccessibilityNodeInfo> exitPromptNodes = checkNode.findAccessibilityNodeInfosByText("再按一次退出");
                if (!exitPromptNodes.isEmpty()) {
                    logMessage("第一次返回后检测到退出提示，已在职位主界面，不执行第二次返回，直接查找职位");
                    handler.postDelayed(() -> {
                        AccessibilityNodeInfo finalRootNode = getRootInActiveWindow();
                        if (finalRootNode != null) {
                            findAndClickJobs(finalRootNode);
                        }
                    }, MAIN_PAGE_LOAD_DELAY);
                    return;
                }
                
                // 检查是否有职位标签且被选中
                List<AccessibilityNodeInfo> tabNodes = checkNode.findAccessibilityNodeInfosByText("职位");
                for (AccessibilityNodeInfo node : tabNodes) {
                    if (node.isSelected()) {
                        logMessage("第一次返回后已在职位主界面(职位标签已选中)，不执行第二次返回，直接查找职位");
                        handler.postDelayed(() -> {
                            AccessibilityNodeInfo finalRootNode = getRootInActiveWindow();
                            if (finalRootNode != null) {
                                findAndClickJobs(finalRootNode);
                            }
                        }, MAIN_PAGE_LOAD_DELAY);
                        return;
                    }
                }
            }
            
            logMessage("执行第二次返回操作");
            performGlobalAction(GLOBAL_ACTION_BACK);
            
            // 延迟后开始查找职位
            handler.postDelayed(() -> {
                logMessage("返回操作完成，开始查找职位");
                AccessibilityNodeInfo finalRootNode = getRootInActiveWindow();
                if (finalRootNode != null) {
                    findAndClickJobs(finalRootNode);
                }
            }, BACK_OPERATION_DELAY);
        }, BACK_OPERATION_DELAY);
    }

    // 添加聊天页面返回操作方法
    private void performChatPageBackOperation() {
        logMessage("执行聊天页面返回操作");
        
        // 先执行一次返回
        performGlobalAction(GLOBAL_ACTION_BACK);
        
        // 延迟后再执行一次返回
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            
            // 延迟后检查是否回到主界面
            handler.postDelayed(() -> {
                AccessibilityNodeInfo checkNode = getRootInActiveWindow();
                if (checkNode != null) {
                    PageType checkPage = getCurrentPageType(checkNode);
                    if (checkPage == PageType.MAIN_LIST) {
                        logMessage("成功回到主界面，开始查找职位");
                        // 增加打招呼计数
                        greetingCount++;
                        logMessage("当前已打招呼次数: " + greetingCount + "/" + MAX_GREETING_COUNT);
                        
                        // 检查是否达到最大打招呼次数
                        if (greetingCount >= MAX_GREETING_COUNT) {
                            logMessage("已达到最大打招呼次数 " + MAX_GREETING_COUNT + "，准备停止服务");
                            handler.postDelayed(() -> {
                                stopService();
                            }, 3000);
                            return;
                        }
                        
                        handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    } else {
                        logMessage("两次返回后未回到主界面，尝试强制返回");
                        forceReturnToMainPage();
                    }
                }
            }, BACK_OPERATION_DELAY);
        }, BACK_OPERATION_DELAY);
    }
    
    // 修改forceReturnToMainPage方法，避免多次点击
    private void forceReturnToMainPage() {
        logMessage("执行强制返回到主界面操作");
        
        // 检查当前页面类型
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            PageType currentPage = getCurrentPageType(rootNode);
            
            // 如果已经在主界面，直接开始查找职位
            if (currentPage == PageType.MAIN_LIST) {
                logMessage("当前已在主界面，直接开始查找职位");
                handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                return;
            }
        }
        
        // 尝试点击底部"职位"标签
        if (rootNode != null) {
            clickPositionTab(rootNode);
            return;
        }
        
        // 如果找不到底部标签，尝试多次返回
        logMessage("找不到底部标签，尝试多次返回");
        
        // 使用更温和的返回策略，每次返回之间增加延迟
        for (int i = 0; i < 3; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }, 1000 * index); // 每次返回之间间隔1秒
        }
        
        // 延迟后检查
        handler.postDelayed(() -> {
            AccessibilityNodeInfo finalNode = getRootInActiveWindow();
            if (finalNode != null) {
                PageType finalPage = getCurrentPageType(finalNode);
                if (finalPage == PageType.MAIN_LIST) {
                    logMessage("强制返回成功，已回到主界面");
                    handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                } else {
                    logMessage("强制返回失败，尝试重启APP");
                    // 最后尝试点击"职位"标签
                    clickPositionTab(finalNode);
                }
            }
        }, BACK_OPERATION_DELAY + 3000); // 增加延迟时间，确保所有返回操作都已完成
    }
    
    // 添加点击底部"职位"标签的方法
    private void clickPositionTab(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return;
        
        // 检查距离上次点击的时间间隔
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPositionTabClickTime < MIN_TAB_CLICK_INTERVAL) {
            logMessage("距离上次点击'职位'标签时间不足" + (MIN_TAB_CLICK_INTERVAL/1000) + "秒，延迟执行");
            
            // 如果已经有待执行的点击，不再重复安排
            if (isTabClickPending) {
                logMessage("已有待执行的'职位'标签点击，跳过");
                return;
            }
            
            // 标记有待执行的点击
            isTabClickPending = true;
            
            // 延迟执行
            handler.postDelayed(() -> {
                isTabClickPending = false;
                clickPositionTab(getRootInActiveWindow());
            }, MIN_TAB_CLICK_INTERVAL - (currentTime - lastPositionTabClickTime) + 1000);
            return;
        }
        
        logMessage("尝试点击底部'职位'标签");
        
        // 通过ID查找底部标签
        List<AccessibilityNodeInfo> tabNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/tv_tab_1");
        if (tabNodes.isEmpty()) {
            // 尝试通过文本查找
            tabNodes = rootNode.findAccessibilityNodeInfosByText("职位");
        }
        
        if (!tabNodes.isEmpty()) {
            for (AccessibilityNodeInfo node : tabNodes) {
                if (node.getText() != null && node.getText().toString().equals("职位")) {
                    logMessage("找到底部'职位'标签，点击返回主界面");
                    clickNode(node);
                    
                    // 更新最后点击时间
                    lastPositionTabClickTime = System.currentTimeMillis();
                    
                    // 延迟后检查是否回到主界面
                    handler.postDelayed(() -> {
                        AccessibilityNodeInfo checkNode = getRootInActiveWindow();
                        if (checkNode != null) {
                            PageType checkPage = getCurrentPageType(checkNode);
                            if (checkPage == PageType.MAIN_LIST) {
                                logMessage("成功回到主界面，开始查找职位");
                                handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                            } else {
                                logMessage("点击'职位'标签后未回到主界面，尝试重启APP");
                                // 不立即重启，而是延迟一段时间后再检查
                                handler.postDelayed(() -> {
                                    AccessibilityNodeInfo finalCheckNode = getRootInActiveWindow();
                                    if (finalCheckNode != null) {
                                        PageType finalCheckPage = getCurrentPageType(finalCheckNode);
                                        if (finalCheckPage == PageType.MAIN_LIST) {
                                            logMessage("延迟检查发现已回到主界面，开始查找职位");
                                            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                                        } else {
                                            logMessage("多次检查后仍未回到主界面，尝试重启APP");
                                            restartBossApp();
                                        }
                                    }
                                }, 2000); // 延迟2秒再次检查
                            }
                        }
                    }, MAIN_PAGE_LOAD_DELAY);
                    
                    return;
                }
            }
        }
        
        // 如果找不到底部标签，尝试通过坐标点击
        logMessage("未找到底部'职位'标签，尝试通过坐标点击");
        if (screenWidth > 0 && screenHeight > 0) {
            // 点击屏幕底部左侧位置（通常是"职位"标签的位置）
            clickAtPosition(screenWidth / 5, (int)(screenHeight * 0.95));
            
            // 更新最后点击时间
            lastPositionTabClickTime = System.currentTimeMillis();
            
            // 延迟后检查
            handler.postDelayed(() -> {
                AccessibilityNodeInfo checkNode = getRootInActiveWindow();
                if (checkNode != null) {
                    PageType checkPage = getCurrentPageType(checkNode);
                    if (checkPage == PageType.MAIN_LIST) {
                        logMessage("坐标点击成功回到主界面");
                        handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    } else {
                        logMessage("坐标点击未能回到主界面，尝试重启APP");
                        // 不立即重启，而是延迟一段时间后再检查
                        handler.postDelayed(() -> {
                            AccessibilityNodeInfo finalCheckNode = getRootInActiveWindow();
                            if (finalCheckNode != null) {
                                PageType finalCheckPage = getCurrentPageType(finalCheckNode);
                                if (finalCheckPage == PageType.MAIN_LIST) {
                                    logMessage("延迟检查发现已回到主界面，开始查找职位");
                                    handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                                } else {
                                    logMessage("多次检查后仍未回到主界面，尝试重启APP");
                                    restartBossApp();
                                }
                            }
                        }, 2000); // 延迟2秒再次检查
                    }
                }
            }, MAIN_PAGE_LOAD_DELAY);
        } else {
            logMessage("无法获取屏幕尺寸，尝试重启APP");
            restartBossApp();
        }
    }
    
    // 添加查找包含指定文本列表的节点的方法
    private void findNodesWithTexts(AccessibilityNodeInfo root, List<String> texts, List<AccessibilityNodeInfo> result) {
        if (root == null) return;
        
        // 检查当前节点
        if (root.getText() != null) {
            String nodeText = root.getText().toString();
            for (String text : texts) {
                if (nodeText.contains(text)) {
                    result.add(root);
                    break;
                }
            }
        }
        
        // 递归检查所有子节点
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                findNodesWithTexts(child, texts, result);
            }
        }
    }

    // 修改handleIntelligentReturn方法，增加延迟检测逻辑
    private void handleIntelligentReturn(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return;
        
        PageType currentPage = getCurrentPageType(rootNode);
        logMessage("智能返回: 当前界面类型为 " + (currentPage != null ? currentPage.toString() : "未知"));
        
        // 如果无法确定界面类型，延迟3秒后再次检测
        if (currentPage == null) {
            logMessage("无法确定界面类型，延迟3秒后再次检测");
            handler.postDelayed(() -> {
                AccessibilityNodeInfo delayedRootNode = getRootInActiveWindow();
                if (delayedRootNode != null) {
                    PageType delayedPage = getCurrentPageType(delayedRootNode);
                    logMessage("延迟检测后界面类型为: " + (delayedPage != null ? delayedPage.toString() : "仍未知"));
                    
                    if (delayedPage != null) {
                        // 如果延迟检测后确定了界面类型，根据类型执行相应操作
                        handleIntelligentReturnByType(delayedPage, delayedRootNode);
                    } else {
                        // 如果仍无法确定界面类型，执行通用返回操作
                        logMessage("延迟检测后仍无法确定界面类型，执行通用返回操作");
                        performSimpleReturn(2); // 执行两次返回操作
                    }
                }
            }, 3000);
            return;
        }
        
        // 如果已确定界面类型，根据类型执行相应操作
        handleIntelligentReturnByType(currentPage, rootNode);
    }
    
    // 添加根据界面类型执行智能返回的方法
    private void handleIntelligentReturnByType(PageType pageType, AccessibilityNodeInfo rootNode) {
        if (pageType == PageType.JOB_DETAIL) {
            // 如果在职位详情页(b)，执行一次返回操作
            logMessage("检测到在职位详情页，执行一次返回操作");
            performGlobalAction(GLOBAL_ACTION_BACK);
            
            // 延迟1500毫秒后检查是否回到主界面
            handler.postDelayed(() -> {
                AccessibilityNodeInfo newRootNode = getRootInActiveWindow();
                if (newRootNode != null) {
                    PageType newPage = getCurrentPageType(newRootNode);
                    if (newPage == PageType.MAIN_LIST) {
                        logMessage("返回成功，已回到主界面");
                        handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    } else {
                        logMessage("返回后仍未回到主界面，尝试强制返回到主界面");
                        forceReturnToMainPage();
                    }
                }
            }, 1500);
        } else if (pageType == PageType.CHAT_PAGE) {
            // 如果在聊天页面(c)，执行两次返回操作
            logMessage("检测到在聊天页面，执行两次返回操作");
            performGlobalAction(GLOBAL_ACTION_BACK);
            
            // 延迟1000毫秒后执行第二次返回
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                
                // 延迟1500毫秒后检查是否回到主界面
                handler.postDelayed(() -> {
                    AccessibilityNodeInfo newRootNode = getRootInActiveWindow();
                    if (newRootNode != null) {
                        PageType newPage = getCurrentPageType(newRootNode);
                        if (newPage == PageType.MAIN_LIST) {
                            logMessage("两次返回成功，已回到主界面");
                            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                        } else {
                            logMessage("两次返回后仍未回到主界面，尝试强制返回到主界面");
                            forceReturnToMainPage();
                        }
                    }
                }, 1500);
            }, 1000);
        } else if (pageType == PageType.MAIN_LIST) {
            // 如果已经在主界面，直接查找职位
            logMessage("检测到已在主界面，直接查找职位");
            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
        } else {
            // 如果是其他未知界面，尝试强制返回到主界面
            logMessage("当前在未知界面，尝试强制返回到主界面");
            forceReturnToMainPage();
        }
    }
    
    // 完整替换整个方法
    private void executeReturnOperation(int times) {
            // 检查距离上次返回操作的时间间隔
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastBackOperationTime < MIN_BACK_INTERVAL) {
                logMessage("距离上次返回操作时间不足" + (MIN_BACK_INTERVAL/1000) + "秒，延迟执行");
                handler.postDelayed(() -> executeReturnOperation(times), MIN_BACK_INTERVAL - (currentTime - lastBackOperationTime) + 1000);
                return;
            }
            
            // 先检查当前是否有"再按一次退出程序"提示
            AccessibilityNodeInfo currentRootNode = getRootInActiveWindow();
            if (currentRootNode != null) {
                List<AccessibilityNodeInfo> exitPromptNodes = currentRootNode.findAccessibilityNodeInfosByText("再按一次退出");
                if (!exitPromptNodes.isEmpty()) {
                    logMessage("检测到退出提示，已在职位主界面，禁止执行返回操作，直接开始查找职位");
                    handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    return;
                }
                
                // 检查当前包名，判断是否已退出BOSS直聘
                if (currentRootNode.getPackageName() != null && 
                    !currentRootNode.getPackageName().toString().equals(BOSS_PACKAGE_NAME)) {
                    logMessage("检测到已退出BOSS直聘，尝试重启APP");
                    handler.postDelayed(() -> restartBossApp(), APP_RESTART_DELAY);
                    return;
                }
            }
            
            // 在返回前记录当前界面类型
            AccessibilityNodeInfo rootNodeBeforeBack = getRootInActiveWindow();
            if (rootNodeBeforeBack != null) {
                previousPageType = getCurrentPageType(rootNodeBeforeBack);
                logMessage("返回前界面类型: " + (previousPageType != null ? previousPageType.toString() : "未知"));
            }
            
            // 更新最后返回操作时间
            lastBackOperationTime = System.currentTimeMillis();
            
            // 执行第一次返回
            performGlobalAction(GLOBAL_ACTION_BACK);
            logMessage("执行第一次返回操作");
            
        // 如果需要多次返回
        if (times > 1) {
            // 延迟后执行第二次返回
            handler.postDelayed(() -> {
                // 检查是否已经回到主界面
                AccessibilityNodeInfo rootNodeAfterFirstBack = getRootInActiveWindow();
                if (rootNodeAfterFirstBack != null) {
                    // 检查是否有"再按一次退出程序"提示
                    List<AccessibilityNodeInfo> exitPromptNodes = rootNodeAfterFirstBack.findAccessibilityNodeInfosByText("再按一次退出");
                    if (!exitPromptNodes.isEmpty()) {
                        logMessage("第一次返回后检测到退出提示，已在职位主界面，禁止继续返回，直接开始查找职位");
                        handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                        return;
                    }
                    
                    // 执行第二次返回
                                lastBackOperationTime = System.currentTimeMillis();
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                logMessage("执行第二次返回操作");
                                
                    // 延迟后检查是否回到主界面
                                handler.postDelayed(() -> {
                        AccessibilityNodeInfo rootNodeAfterSecondBack = getRootInActiveWindow();
                        if (rootNodeAfterSecondBack != null) {
                                        // 检查是否有"再按一次退出程序"提示
                            List<AccessibilityNodeInfo> secondExitPromptNodes = rootNodeAfterSecondBack.findAccessibilityNodeInfosByText("再按一次退出");
                            if (!secondExitPromptNodes.isEmpty()) {
                                            logMessage("第二次返回后检测到退出提示，已在职位主界面，直接开始查找职位");
                                            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                                            return;
                                        }
                                        }
                    }, 1500);
                                    }
            }, BACK_OPERATION_DELAY);
                        } else {
            // 如果只需要返回一次，延迟后检查结果
                        handler.postDelayed(() -> {
                AccessibilityNodeInfo rootNodeAfterBack = getRootInActiveWindow();
                if (rootNodeAfterBack != null) {
                                                        // 检查是否有"再按一次退出程序"提示
                    List<AccessibilityNodeInfo> exitPromptNodes = rootNodeAfterBack.findAccessibilityNodeInfosByText("再按一次退出");
                    if (!exitPromptNodes.isEmpty()) {
                        logMessage("返回后检测到退出提示，已在职位主界面，直接开始查找职位");
                                                            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    }
                }
            }, 1500);
    }
}

    // 修改sendGreetingMessage方法，添加计数功能
    private void sendGreetingMessage() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        
        // 检查是否已经发送过打招呼消息
        if (greetingDetected || greetingSent) {
            logMessage("已发送过打招呼消息，跳过");
            // 已发送过消息，直接返回主界面
            performDoubleBackToMainPage();
            return;
        }
        
        // 等待系统自动发送打招呼消息
        logMessage("等待系统自动发送打招呼消息");
        
        // 延迟3秒后检查是否已发送打招呼消息
        handler.postDelayed(() -> {
            AccessibilityNodeInfo newRootNode = getRootInActiveWindow();
            if (newRootNode != null) {
                boolean hasGreeting = checkIfGreetingSent(newRootNode);
                if (hasGreeting) {
                    logMessage("检测到系统已自动发送打招呼消息，准备返回主界面");
                    performDoubleBackToMainPage();
                } else {
                    logMessage("等待3秒后仍未检测到打招呼消息，继续等待");
                    // 再延迟3秒检查
                    handler.postDelayed(() -> {
                        AccessibilityNodeInfo finalRootNode = getRootInActiveWindow();
                        if (finalRootNode != null) {
                            boolean finalHasGreeting = checkIfGreetingSent(finalRootNode);
                            if (finalHasGreeting) {
                                logMessage("最终检测到系统已自动发送打招呼消息，准备返回主界面");
                                performDoubleBackToMainPage();
                            } else {
                                logMessage("多次等待后仍未检测到打招呼消息，尝试返回主界面");
                                performDoubleBackToMainPage();
                            }
                        }
                    }, 3000);
                }
            }
        }, 3000);
    }

    // 添加一个方法查找具有精确文本的节点
    private void findNodesWithExactText(AccessibilityNodeInfo root, String text, List<AccessibilityNodeInfo> result) {
        if (root == null) return;
        
        // 检查当前节点
        if (root.getText() != null && text.equals(root.getText().toString())) {
            result.add(root);
        }
        
        // 递归检查所有子节点
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                findNodesWithExactText(child, text, result);
            }
        }
    }

    // 添加一个方法查找节点的可点击父节点
    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                return parent;
            }
            AccessibilityNodeInfo temp = parent;
            parent = parent.getParent();
            // 避免内存泄漏
            if (temp != node) {
                temp.recycle();
            }
        }
        
        return null;
    }
    
    // 添加一个方法获取所有节点
    private List<AccessibilityNodeInfo> getAllNodesFromRoot(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        if (root == null) return nodes;
        
        // 使用广度优先搜索遍历所有节点
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.remove(0);
            nodes.add(node);
            
            // 添加所有子节点到队列
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        
        return nodes;
    }

    // 添加缺失的performBackToMainPage方法
    private void performBackToMainPage() {
        logMessage("执行返回主界面操作");
        // 重置连续返回计数
        consecutiveBackCount = 0;
        
        // 执行返回操作并检查是否回到主界面
        performSingleBackAndCheck(
            // 如果返回到主界面
            () -> {
                handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
            },
            // 如果返回后不在主界面，再次尝试返回
            () -> {
                logMessage("返回后仍不在主界面，再次尝试返回");
                consecutiveBackCount = 0;
                performSingleBackAndCheck(
                    // 如果第二次返回到主界面
                    () -> {
                        handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                    },
                    // 如果第二次返回后仍不在主界面
                    () -> {
                        logMessage("两次返回后仍不在主界面，暂停操作");
                        stopService();
                    }
                );
            }
        );
    }

    // 修改findAndClickBottomButton方法，点击沟通按钮后直接执行返回操作
    private void findAndClickBottomButton(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return;
        
        logMessage("尝试查找并点击底部沟通按钮");
        
        // 1. 首先尝试通过ID查找底部按钮
        List<AccessibilityNodeInfo> buttonNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/btn_chat");
        if (!buttonNodes.isEmpty()) {
            logMessage("通过ID找到底部按钮，准备点击");
            clickNode(buttonNodes.get(0));
            resetConsecutiveBackCount();
            currentState = State.COMMUNICATING;
            
            // 点击沟通按钮后，直接假设进入了聊天页面，延迟后执行返回操作
            handler.postDelayed(() -> {
                // 先检查是否有系统限制提示
                AccessibilityNodeInfo chatRootNode = getRootInActiveWindow();
                if (chatRootNode != null) {
                    // 检查是否有聊天页面特有的功能按钮
                    List<AccessibilityNodeInfo> sendResumeNodes = chatRootNode.findAccessibilityNodeInfosByText("发简历");
                    List<AccessibilityNodeInfo> changePhoneNodes = chatRootNode.findAccessibilityNodeInfosByText("换电话");
                    List<AccessibilityNodeInfo> changeWechatNodes = chatRootNode.findAccessibilityNodeInfosByText("换微信");
                    List<AccessibilityNodeInfo> notInterestedNodes = chatRootNode.findAccessibilityNodeInfosByText("不感兴趣");
                    
                    // 如果检测到聊天页面特征，立即处理聊天页面并返回
                    int featureCount = 0;
                    if (!sendResumeNodes.isEmpty()) featureCount++;
                    if (!changePhoneNodes.isEmpty()) featureCount++;
                    if (!changeWechatNodes.isEmpty()) featureCount++;
                    if (!notInterestedNodes.isEmpty()) featureCount++;
                    
                    if (featureCount >= 2) {
                        logMessage("在滑动前检测到聊天页面特征，立即处理聊天页面");
                        handleChatPageDetected();
                        return;
                    }
                }
                
                logMessage("点击沟通按钮后延迟执行，直接处理为聊天页面");
                
                // 只有未计数的职位才增加打招呼计数
                if (!currentJobId.isEmpty() && !hasCountedCurrentJob) {
                    // 增加打招呼计数
                    greetingCount++;
                    totalCount++; // 同时增加总处理职位数
                    hasCountedCurrentJob = true;
                    logMessage("【新】处理新职位，当前已打招呼次数: " + greetingCount + "/" + MAX_GREETING_COUNT + "，总处理职位: " + totalCount);
                    
                    // 检查是否达到最大打招呼次数
                    if (greetingCount >= MAX_GREETING_COUNT && !isServiceStopping) {
                        logMessage("【重要】已达到最大打招呼次数 " + MAX_GREETING_COUNT + "，准备停止服务");
                        isServiceStopping = true;
                        handler.postDelayed(() -> {
                            stopService();
                        }, 2000);
                        return;
                    }
                } else {
                    logMessage("【跳过】当前职位已计数或无效，不增加打招呼次数");
                }
                
                // 执行双重返回操作
                performDoubleBackToMainPage();
            }, 3000);
            return;
        }
        
        // 2. 尝试通过文本查找"立即沟通"按钮
        List<AccessibilityNodeInfo> immediateNodes = rootNode.findAccessibilityNodeInfosByText("立即沟通");
        if (!immediateNodes.isEmpty()) {
            logMessage("找到'立即沟通'按钮，准备点击");
            for (AccessibilityNodeInfo node : immediateNodes) {
                if (node.isClickable()) {
                    clickNode(node);
                    logMessage("点击了'立即沟通'按钮");
                    resetConsecutiveBackCount();
                    currentState = State.COMMUNICATING;
                    
                    // 点击沟通按钮后，直接假设进入了聊天页面，延迟后执行返回操作
                    handler.postDelayed(() -> {
                        logMessage("点击立即沟通按钮后延迟执行，直接处理为聊天页面");
                        // 增加打招呼计数
                        greetingCount++;
                        logMessage("当前已打招呼次数: " + greetingCount + "/" + MAX_GREETING_COUNT);
                        
                        // 不再检查是否达到最大打招呼次数
                        // 移除下面的检查代码
                        // if (greetingCount >= MAX_GREETING_COUNT && !isServiceStopping) {
                        //    ...
                        // }
                        
                        // 执行双重返回操作
                        performDoubleBackToMainPage();
                    }, 2000);
                    return;
                }
            }
        }
        
        // 3. 尝试通过文本查找"继续沟通"按钮
        List<AccessibilityNodeInfo> continueNodes = rootNode.findAccessibilityNodeInfosByText("继续沟通");
        if (!continueNodes.isEmpty()) {
            logMessage("找到'继续沟通'按钮，准备点击");
            for (AccessibilityNodeInfo node : continueNodes) {
                if (node.isClickable()) {
                    clickNode(node);
                    logMessage("点击了'继续沟通'按钮");
                    resetConsecutiveBackCount();
                    currentState = State.COMMUNICATING;
                    return;
                }
            }
        }
        
        // 4. 尝试查找底部的任何按钮
        List<AccessibilityNodeInfo> allButtons = findNodesByClassName(rootNode, "android.widget.Button");
        for (AccessibilityNodeInfo button : allButtons) {
            if (button.getText() != null) {
                String buttonText = button.getText().toString();
                if (buttonText.contains("沟通") || buttonText.contains("交谈") || buttonText.contains("聊一聊")) {
                    logMessage("找到底部按钮: " + buttonText);
                    clickNode(button);
                    resetConsecutiveBackCount();
                    currentState = State.COMMUNICATING;
                    return;
                }
            }
        }
        
        // 5. 尝试查找底部区域的可点击元素
        List<AccessibilityNodeInfo> bottomNodes = findNodesInBottomArea(rootNode);
        for (AccessibilityNodeInfo node : bottomNodes) {
            if (node.isClickable()) {
                logMessage("找到底部区域可点击元素，尝试点击");
                clickNode(node);
                resetConsecutiveBackCount();
                currentState = State.COMMUNICATING;
                return;
            }
        }
        
        // 6. 如果仍然找不到，尝试点击屏幕底部中央位置
        logMessage("未找到底部按钮，尝试点击屏幕底部中央位置");
        if (screenWidth > 0 && screenHeight > 0) {
            clickAtPosition(screenWidth / 2, (int)(screenHeight * 0.9));
            resetConsecutiveBackCount();
            currentState = State.COMMUNICATING;
            return;
        }
        
        // 7. 最后尝试通过坐标直接点击屏幕底部中央位置
        logMessage("尝试通过多点触控模拟点击底部按钮");
        
        // 先点击底部中央位置
        clickAtPosition(screenWidth / 2, (int)(screenHeight * 0.92));
        
        // 延迟100毫秒后，再点击稍微偏上一点的位置
        handler.postDelayed(() -> {
            clickAtPosition(screenWidth / 2, (int)(screenHeight * 0.88));
        }, 100);
        
        // 延迟200毫秒后，再点击稍微偏下一点的位置
        handler.postDelayed(() -> {
            clickAtPosition(screenWidth / 2, (int)(screenHeight * 0.95));
        }, 200);
        
        resetConsecutiveBackCount();
        currentState = State.COMMUNICATING;
    }

    // 在类中添加 handlePageByType 方法
    // 添加根据页面类型处理的方法
    private void handlePageByType(PageType pageType, AccessibilityNodeInfo rootNode) {
        if (pageType == PageType.MAIN_LIST) {
            // 如果在主界面，查找职位
            logMessage("检测到主界面，准备查找职位");
            handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
        } else if (pageType == PageType.JOB_DETAIL) {
            // 如果在职位详情页，点击沟通按钮
            logMessage("检测到职位详情页，准备点击沟通按钮");
            findAndClickBottomButton(rootNode);
        }
    }

    // 添加启动BOSS直聘APP的方法
    public static void launchBossApp(android.content.Context context) {
        try {
            // 方法1：通过包名启动BOSS直聘APP
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(BOSS_PACKAGE_NAME);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                Log.d(TAG, "通过包名启动BOSS直聘APP");
                return;
            }
            
            // 方法2：通过指定Activity启动
            Intent intent = new Intent();
            intent.setClassName(BOSS_PACKAGE_NAME, BOSS_MAIN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "通过指定Activity启动BOSS直聘APP");
        } catch (Exception e) {
            Log.e(TAG, "启动BOSS直聘APP失败: " + e.getMessage());
        }
    }

    // 查找符合条件的职位节点
    private List<AccessibilityNodeInfo> findJobNodes(AccessibilityNodeInfo rootNode) {
        List<AccessibilityNodeInfo> jobNodes = new ArrayList<>();
        if (rootNode == null) return jobNodes;
        
        // 查找所有可能的职位卡片
        List<AccessibilityNodeInfo> jobCards = findJobCards(rootNode);
        logMessage("找到 " + jobCards.size() + " 个职位卡片");
        
        // 遍历职位卡片，查找符合关键词的职位
        for (AccessibilityNodeInfo jobCard : jobCards) {
            // 获取职位卡片上的所有文本
            List<String> cardTexts = getAllTextsFromNode(jobCard);
            if (cardTexts.isEmpty()) {
                continue;
            }
            
            // 将所有文本合并为一个字符串，方便检查关键词
            String cardText = String.join(" ", cardTexts);
            
            // 检查是否包含关键词
            boolean containsKeyword = false;
            for (String keyword : keywords) {
                if (cardText.toLowerCase().contains(keyword.toLowerCase())) {
                    containsKeyword = true;
                    logMessage("找到关键词: " + keyword + " 在职位: " + cardText);
                    break;
                }
            }
            
            // 如果包含关键词，添加到结果列表
            if (containsKeyword) {
                jobNodes.add(jobCard);
            }
        }
        
        return jobNodes;
    }

    // 获取节点的文本内容
    private String getNodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        // 尝试获取节点自身的文本
        CharSequence text = node.getText();
        if (text != null) {
            return text.toString();
        }
        
        // 如果节点自身没有文本，获取所有子节点的文本并合并
        List<String> texts = getAllTextsFromNode(node);
        if (!texts.isEmpty()) {
            return String.join(" ", texts);
        }
        
        // 如果没有找到任何文本，生成一个唯一标识
        return generateNodeId(node);
    }

    // 添加查找底部区域节点的方法
    private List<AccessibilityNodeInfo> findNodesInBottomArea(AccessibilityNodeInfo rootNode) {
        List<AccessibilityNodeInfo> bottomNodes = new ArrayList<>();
        if (rootNode == null) return bottomNodes;
        
        // 获取屏幕尺寸
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        
        // 遍历所有节点，找出位于屏幕底部区域的节点
        List<AccessibilityNodeInfo> allNodes = getAllNodesFromRoot(rootNode);
        for (AccessibilityNodeInfo node : allNodes) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            
            // 检查节点是否在屏幕底部区域（底部20%的区域）
            if (bounds.bottom > screenHeight * 0.8) {
                bottomNodes.add(node);
            }
        }
        
        return bottomNodes;
    }

    // 添加performSimpleReturn方法
    private void performSimpleReturn(int times) {
        logMessage("执行简单返回操作，次数: " + times);
        
        // 检查返回次数是否合理
        if (times <= 0 || times > 3) {
            logMessage("返回次数不合理，取消操作");
            return;
        }
        
        // 执行第一次返回
        performGlobalAction(GLOBAL_ACTION_BACK);
        
        // 如果需要多次返回，延迟执行
        if (times > 1) {
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                
                // 如果需要第三次返回，再次延迟执行
                if (times > 2) {
                    handler.postDelayed(() -> {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }, BACK_OPERATION_DELAY);
                }
            }, BACK_OPERATION_DELAY);
        }
        
        // 延迟后检查当前页面
        handler.postDelayed(() -> {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                PageType currentPage = getCurrentPageType(rootNode);
                logMessage("返回操作后当前页面: " + (currentPage != null ? currentPage.toString() : "未知"));
                
                if (currentPage == PageType.MAIN_LIST) {
                    // 如果返回到主界面，开始查找职位
                    logMessage("成功返回到主界面，开始查找职位");
                    handler.postDelayed(() -> findAndClickJobs(getRootInActiveWindow()), MAIN_PAGE_LOAD_DELAY);
                }
            }
        }, BACK_OPERATION_DELAY * times + 1000);
    }

    // 此方法已废弃，不再需要延迟检查
    private void delayedCheckDetailPage() {
        return;
    }
    
    // 添加滑动向下方法
    private void scrollDown() {
        // 先检查当前是否在聊天页面
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            // 检查是否有聊天页面特有的功能按钮
            List<AccessibilityNodeInfo> sendResumeNodes = rootNode.findAccessibilityNodeInfosByText("发简历");
            List<AccessibilityNodeInfo> changePhoneNodes = rootNode.findAccessibilityNodeInfosByText("换电话");
            List<AccessibilityNodeInfo> changeWechatNodes = rootNode.findAccessibilityNodeInfosByText("换微信");
            List<AccessibilityNodeInfo> notInterestedNodes = rootNode.findAccessibilityNodeInfosByText("不感兴趣");
            
            // 如果检测到聊天页面特征，立即处理聊天页面并返回
            int featureCount = 0;
            if (!sendResumeNodes.isEmpty()) featureCount++;
            if (!changePhoneNodes.isEmpty()) featureCount++;
            if (!changeWechatNodes.isEmpty()) featureCount++;
            if (!notInterestedNodes.isEmpty()) featureCount++;
            
            if (featureCount >= 2) {
                logMessage("在滑动前检测到聊天页面特征，立即处理聊天页面");
                handleChatPageDetected();
                return;
            }
        }
        
        logMessage("执行向下滑动操作");
        
        // 标记正在滑动
        isScrolling = true;
        lastScrollTime = System.currentTimeMillis();
        
        // 获取屏幕尺寸
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        
        // 创建滑动路径
        Path path = new Path();
        path.moveTo(screenWidth / 2, screenHeight * 0.7f);  // 从屏幕中间偏下位置开始
        path.lineTo(screenWidth / 2, screenHeight * 0.3f);  // 滑动到屏幕中间偏上位置
        
        // 创建手势描述
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
        
        // 执行手势
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                logMessage("滑动手势完成");
                
                // 延迟后标记滑动完成
                handler.postDelayed(() -> {
                    isScrolling = false;
                    logMessage("滑动完成，等待5秒后再次查找职位");
                    
                    // 延迟后再次查找职位
                    handler.postDelayed(() -> {
                        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                        if (rootNode != null) {
                            findAndClickJobs(rootNode);
                        }
                    }, SCROLL_COOLDOWN);
                }, 1000);
            }
            
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                logMessage("滑动手势被取消");
                isScrolling = false;
            }
        }, null);
    }

    // 修改isInMainPage方法，优化职位主界面检测
    private boolean isInMainPage(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return false;
        
        // 首先检查是否有"再按一次退出程序"提示
        List<AccessibilityNodeInfo> exitPromptNodes = rootNode.findAccessibilityNodeInfosByText("再按一次退出");
        if (!exitPromptNodes.isEmpty()) {
            logMessage("检测到退出提示，判断为职位主界面");
            return true;
        }
        
        // 检查是否在职位详情页 - 如果是职位详情页，则不是主界面
        List<AccessibilityNodeInfo> communicateNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/btn_chat");
        for (AccessibilityNodeInfo node : communicateNodes) {
            if (node.getText() != null) {
                String buttonText = node.getText().toString();
                if (buttonText.equals("立即沟通") || buttonText.equals("继续沟通")) {
                    // 这是职位详情页，不是主界面
                    return false;
                }
            }
        }
        
        // 通过文本查找"立即沟通"或"继续沟通"按钮
        List<AccessibilityNodeInfo> immediateNodes = rootNode.findAccessibilityNodeInfosByText("立即沟通");
        List<AccessibilityNodeInfo> continueNodes = rootNode.findAccessibilityNodeInfosByText("继续沟通");
        if (!immediateNodes.isEmpty() || !continueNodes.isEmpty()) {
            // 这是职位详情页，不是主界面
            return false;
        }
        
        // 检查是否有职位标签且被选中
        List<AccessibilityNodeInfo> tabNodes = rootNode.findAccessibilityNodeInfosByText("职位");
        for (AccessibilityNodeInfo node : tabNodes) {
            if (node.isSelected()) {
                logMessage("检测到底部职位标签被选中，判断为职位主界面");
                return true;
            }
        }
        
        // 检查是否有推荐/附近/最新标签
        List<AccessibilityNodeInfo> recommendNodes = rootNode.findAccessibilityNodeInfosByText("推荐");
        List<AccessibilityNodeInfo> nearbyNodes = rootNode.findAccessibilityNodeInfosByText("附近");
        List<AccessibilityNodeInfo> newestNodes = rootNode.findAccessibilityNodeInfosByText("最新");
        
        if (!recommendNodes.isEmpty() || !nearbyNodes.isEmpty() || !newestNodes.isEmpty()) {
            // 同时检查是否有职位列表
            List<AccessibilityNodeInfo> jobListNodes = findJobCards(rootNode);
            if (!jobListNodes.isEmpty()) {
                logMessage("检测到推荐/附近/最新标签和职位列表，判断为职位主界面");
                return true;
            }
        }
        
        return false;
    }

    // 添加检查是否在职位详情页的方法
    private boolean isInJobDetailPage(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return false;
        
        // 检查是否有"立即沟通"或"继续沟通"按钮
        List<AccessibilityNodeInfo> communicateNodes = rootNode.findAccessibilityNodeInfosByViewId("com.hpbr.bosszhipin:id/btn_chat");
        for (AccessibilityNodeInfo node : communicateNodes) {
            if (node.getText() != null) {
                String buttonText = node.getText().toString();
                if (buttonText.equals("立即沟通") || buttonText.equals("继续沟通")) {
                    logMessage("检测到" + buttonText + "按钮，判断为职位详情页");
                    return true;
                }
            }
        }
        
        // 通过文本查找"立即沟通"或"继续沟通"按钮
        List<AccessibilityNodeInfo> immediateNodes = rootNode.findAccessibilityNodeInfosByText("立即沟通");
        List<AccessibilityNodeInfo> continueNodes = rootNode.findAccessibilityNodeInfosByText("继续沟通");
        if (!immediateNodes.isEmpty() || !continueNodes.isEmpty()) {
            logMessage("检测到立即沟通/继续沟通按钮，判断为职位详情页");
            return true;
        }
        
        // 检查是否有职位描述、公司介绍等文字
        List<AccessibilityNodeInfo> jobDescNodes = rootNode.findAccessibilityNodeInfosByText("职位描述");
        List<AccessibilityNodeInfo> companyIntroNodes = rootNode.findAccessibilityNodeInfosByText("公司介绍");
        if (!jobDescNodes.isEmpty() && !companyIntroNodes.isEmpty()) {
            logMessage("检测到职位描述和公司介绍文本，判断为职位详情页");
            return true;
        }
        
        return false;
    }

    // 添加服务看门狗相关变量
    private Handler serviceWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable serviceWatchdogRunnable;
    private static final long SERVICE_WATCHDOG_INTERVAL = 60000; // 每分钟检查一次服务状态

    // 增加一个标记，记录当前处理的职位ID，避免重复计数
    private String currentJobId = "";
    private boolean hasCountedCurrentJob = false;








    
    // 发送通知提醒
    private void sendNotification(String title, String content) {
        // 简单记录日志，实际上这里可以实现发送系统通知
        logMessage("系统通知: " + title + " - " + content);
    }

    // 设置一个调试开关变量
    private static final boolean DEBUG_LOG_ALL_NODES = false; // 设置为false关闭详细日志
    

} 