package com.jrasp.agent.module.rce.algorithm;

import com.jrasp.agent.api.Module;
import com.jrasp.agent.api.*;
import com.jrasp.agent.api.algorithm.Algorithm;
import com.jrasp.agent.api.algorithm.AlgorithmManager;
import com.jrasp.agent.api.annotation.Information;
import com.jrasp.agent.api.annotation.RaspResource;
import com.jrasp.agent.api.log.RaspLog;
import com.jrasp.agent.api.request.AttackInfo;
import com.jrasp.agent.api.request.Context;
import com.jrasp.agent.api.util.ParamSupported;
import com.jrasp.agent.api.util.StackTrace;
import org.kohsuke.MetaInfServices;

import java.util.*;

@MetaInfServices(Module.class)
@Information(id = "rce-algorithm", author = "jrasp")
public class RceAlgorithm extends ModuleLifecycleAdapter implements Module, Algorithm {

    @RaspResource
    private AlgorithmManager algorithmManager;

    @RaspResource
    private RaspLog logger;

    @RaspResource
    private String metaInfo;

    @RaspResource
    private RaspConfig raspConfig;

    private volatile Integer rceAction = 0;

    /**
     * 命令执行白名单
     */
    private volatile Set<String> rceWhiteSet = new HashSet<String>();

    /**
     * 命令执行黑名单
     */
    private volatile List<String> rceBlockList = Arrays.asList("curl", "wget", "echo", "touch", "gawk", "telnet",
            "xterm", "perl", "python", "python3", "ruby", "lua", "whoami", "dir", "ls", "ping", "ip", "cat",
            "type", "php", "pwd", "ifconfig", "ipconfig", "alias", "export", "nc", "crontab", "find", "wmic", "net",
            "tac", "more", "bzmore", "less", "bzless", "head", "tail", "nl", "sed", "sort", "uniq", "rev", "od", "vim",
            "vi", "man", "paste", "grep", "file", "dd", "systeminfo", "findstr", "tasklist", "netstat", "netsh",
            "powershell", "for", "arp", "quser", "chmod", "useradd", "hostname", "pwd", "cd", "cp", "mv", "history",
            "tar", "zip", "route", "uname", "id", "passwd", "rpm", "dmesg", "env", "ps", "top", "dpkg", "ss", "lsof",
            "chkconfig"
    );

    private Set<String> rceDangerStackSet = new HashSet<String>(Arrays.asList(
            "com.thoughtworks.xstream.XStream.unmarshal",
            "java.beans.XMLDecoder.readObject",
            "java.io.ObjectInputStream.readObject",
            "org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.readObject",
            "com.alibaba.fastjson.JSON.parse",
            "com.fasterxml.jackson.databind.ObjectMapper.readValue",
            "payload.execCommand",
            "net.rebeyond.behinder",
            "org.springframework.expression.spel.support.ReflectiveMethodExecutor.execute",
            "freemarker.template.utility.Execute.exec",
            "freemarker.core.Expression.eval",
            "bsh.Reflect.invokeMethod",
            "org.jboss.el.util.ReflectionUtil.invokeMethod",
            "org.codehaus.groovy.runtime.ProcessGroovyMethods.execute",
            "org.codehaus.groovy.runtime.callsite.AbstractCallSite.call",
            "ScriptFunction.invoke",
            "com.caucho.hessian.io.HessianInput.readObject",
            "org.apache.velocity.runtime.parser.node.ASTMethod.execute",
            "org.apache.commons.jexl3.internal.Interpreter.call",
            "javax.script.AbstractScriptEngine.eval",
            "javax.el.ELProcessor.getValue",
            "ognl.OgnlRuntime.invokeMethod",
            "javax.naming.InitialContext.lookup",
            "org.mvel2.MVEL.executeExpression",
            "org.mvel.MVEL.executeExpression",
            "ysoserial.Pwner",
            "org.yaml.snakeyaml.Yaml.load",
            "org.mozilla.javascript.Context.evaluateString",
            "command.Exec.equals",
            "java.lang.ref.Finalizer.runFinalizer",
            "java.sql.DriverManager.getConnection"
    ));

    @Override
    public boolean update(Map<String, String> configMaps) {
        this.rceAction = ParamSupported.getParameter(configMaps, "rce_action", Integer.class, rceAction);
        this.rceWhiteSet = ParamSupported.getParameter(configMaps, "rce_white_list", Set.class, rceWhiteSet);
        this.rceBlockList = ParamSupported.getParameter(configMaps, "rce_block_list", List.class, rceBlockList);
        this.rceDangerStackSet = ParamSupported.getParameter(configMaps, "rce_danger_stack_list", Set.class, rceDangerStackSet);
        return true;
    }

    @Override
    public String getType() {
        return "rce";
    }

    @Override
    public void check(Context context, Object... parameters) throws Exception {
        if (!raspConfig.isCheckDisable() && rceAction > -1) {
            // 命令执行白名单
            String cmd = (String) parameters[0];
            List<String> tokens = getTokens(cmd);
            String javaCmd = tokens.get(0);
            if (rceWhiteSet.contains(javaCmd)) {
                return;
            }
            // 检测算法1： 用户输入后门
            // 用户命令是否包含在参数列表中
            if (context != null) {
                String includeParameter = include(context.getParametersString(), tokens);
                if (includeParameter != null) {
                    doActionCtl(rceAction, context, cmd, "rce token contains in http parameters", includeParameter, 80);
                    return;
                }
                String includeHeader = include(context.getHeaderString(), tokens);
                if (includeHeader != null) {
                    doActionCtl(rceAction, context, cmd, "rce token contains in http headers", includeHeader, 80);
                    return;
                }
            }

            //  检测算法2： 包含敏感字符
            for (String item : rceBlockList) {
                if (javaCmd.contains(item)) {
                    doActionCtl(rceAction, context, cmd, "java cmd [" + item + "] in black list.", "", 80);
                    return;
                }
            }

            // 检测算法3： 栈特征
            String[] stackTraceString = StackTrace.getStackTraceString(100, false);
            for (String stack : stackTraceString) {
                if (rceDangerStackSet.contains(stack)) {
                    doActionCtl(rceAction, context, cmd, "danger rce stack: " + stack, "", 90);
                    return;
                }
            }

            // 检测算法4：命令执行监控
            doActionCtl(rceAction, context, cmd, "log all rce", "", 50);
        }
    }

    private String include(String httpParameters, List<String> cmdArgs) {
        if (httpParameters != null) {
            for (String item : cmdArgs) {
                if (httpParameters.contains(item)) {
                    return item;
                }
            }
        }
        return null;
    }

    private void doActionCtl(int action, Context context, String cmd, String checkType, String message, int level) throws ProcessControlException {
        if (action > -1) {
            boolean enableBlock = action == 1;
            AttackInfo attackInfo = new AttackInfo(context, metaInfo, cmd, enableBlock, getType(), checkType, message, level);
            logger.attack(attackInfo);
            if (enableBlock) {
                ProcessController.throwsImmediatelyAndSendResponse(attackInfo, raspConfig, new RuntimeException("rce block by jrasp."));
            }
        }
    }

    @Override
    public String getDescribe() {
        return "rce check algorithm";
    }

    public static List<String> getTokens(String str) {
        List<String> tokens = new ArrayList<String>();
        StringTokenizer tokenizer = new StringTokenizer(str);
        while (tokenizer.hasMoreElements()) {
            tokens.add(tokenizer.nextToken());
        }
        return tokens;
    }

    @Override
    public void loadCompleted() {
        algorithmManager.register(this);
    }

}
