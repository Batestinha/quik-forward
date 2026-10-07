package dev.octoshrimpy.quik.keepalive;
import android.os.Bundle;

interface IKeepAliveCallService {
    Bundle queryCalls(long since) = 0;
    void destroy() = 16777114;
}
