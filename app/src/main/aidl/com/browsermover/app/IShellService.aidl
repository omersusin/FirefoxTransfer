package com.browsermover.app;

interface IShellService {
    String exec(String command);
    void destroy();
}
