package top.chuyb.fakeScreen;

/** 通过显示服务重新应用屏幕状态及系统保存的亮度。 */
final class DisplayServiceRecovery {
    static final int STATE_UNKNOWN = 0;
    static final int STATE_OFF = 1;

    interface PowerRequest {
        boolean request(int state) throws Exception;
    }

    static final class Result {
        final boolean offAccepted;
        final boolean resetAccepted;
        final Exception offError;
        final Exception resetError;

        Result(boolean offAccepted, boolean resetAccepted, Exception offError, Exception resetError) {
            this.offAccepted = offAccepted;
            this.resetAccepted = resetAccepted;
            this.offError = offError;
            this.resetError = resetError;
        }

        boolean isSuccessful() {
            return offAccepted && resetAccepted;
        }
    }

    private DisplayServiceRecovery() {
    }

    static Result recover(PowerRequest powerRequest) {
        boolean offAccepted = false;
        boolean resetAccepted = false;
        Exception offError = null;
        Exception resetError = null;
        try {
            offAccepted = powerRequest.request(STATE_OFF);
        } catch (Exception error) {
            offError = error;
        } finally {
            // OFF 即使抛出异常也可能已经生效，必须继续请求恢复。
            try {
                resetAccepted = powerRequest.request(STATE_UNKNOWN);
            } catch (Exception error) {
                resetError = error;
            }
        }
        return new Result(offAccepted, resetAccepted, offError, resetError);
    }
}
