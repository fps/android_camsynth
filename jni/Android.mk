LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := synth
LOCAL_SRC_FILES := synth.c

include $(BUILD_SHARED_LIBRARY)
