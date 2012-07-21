LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_CPP_FEATURES += exceptions

LOCAL_C_INCLUDES := $(LOCAL_PATH)/stk-4.4.3/include
LOCAL_MODULE    := synth
LOCAL_SRC_FILES := synth.cpp 


include $(BUILD_SHARED_LIBRARY)
