LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := usbiso
LOCAL_SRC_FILES := usbiso.c
LOCAL_CFLAGS := -O2 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
