package io.agentmanager.framework.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentmanager.framework.controller.AguiEventCodec;

/**
 * AG-UI 输出面装配（agui-native-parity-design §10）：agent.agui.enabled=true 时注册
 * pa 编码引擎与配置绑定；关闭时零装配（端点 404、无 codec bean、无存储行为变化）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.agui.enabled", havingValue = "true")
@EnableConfigurationProperties(AguiProperties.class)
public class AguiConfig {

    @Bean
    public AguiEventCodec aguiEventCodec(AguiProperties props) {
        return new AguiEventCodec(props);
    }
}
