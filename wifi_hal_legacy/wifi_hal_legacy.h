/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef WIFI_HAL_LEGACY_WIFI_HAL_LEGACY_H_
#define WIFI_HAL_LEGACY_WIFI_HAL_LEGACY_H_

#include <functional>
#include <set>
#include <thread>

#include <android/hardware/wifi/1.0/IWifi.h>
#include <hardware_legacy/wifi_hal.h>
#include <utils/Looper.h>

namespace android {
namespace hardware {
namespace wifi {

using android::hardware::Status;
using namespace android::hardware::wifi::V1_0;

class WifiHalLegacy : public IWifi {
 public:
  WifiHalLegacy(android::sp<android::Looper>& looper);

  Status registerEventCallback(const sp<IWifiEventCallback>& callback) override;

  Status start(uint32_t cmd_id) override;
  Status stop(uint32_t cmd_id) override;

  Status getChip(std::function<void(const sp<IWifiChip>& chip)> cb) override;

 private:
  static WifiHalLegacy* global_instance_;

  android::sp<Looper> looper_;
  std::set<sp<IWifiEventCallback>> callbacks_;

  enum class State {
    STOPPED,
    STARTED,
    STOPPING
  };

  State state_;
  wifi_hal_fn hal_func_table_;
  wifi_handle hal_handle_;
  std::thread event_loop_thread_;

  // Variables to hold state while stopping the HAL
  uint32_t pending_stop_cmd_id_;
  bool awaiting_hal_cleanup_command_;
  bool awaiting_hal_event_loop_termination_;

  /**
   * Called to indicate that the HAL implementation cleanup may be complete and
   * the rest of HAL cleanup should be performed.
   */
  void FinishHalCleanup();

  /**
   * Entry point for HAL event loop thread. Handles cleanup when terminating.
   */
  void DoHalEventLoop();

  /**
   * Post a task to be executed on the main thread
   */
  void PostTask(const std::function<void()>& callback);
};

}  // namespace wifi
}  // namespace hardware
}  // namespace android

#endif  // WIFI_HAL_LEGACY_WIFI_HAL_LEGACY_H_
