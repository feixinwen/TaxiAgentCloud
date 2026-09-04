package com.fancy.taxiagent.agent.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.model.DashScopeChatGateway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类器误分类回归评测（真实调用 qwen，费用+网络依赖，默认跳过）。
 *
 * <p>运行方式：CLASSIFIER_EVAL=true 且环境含 DashScope key 时手动执行
 * {@code CLASSIFIER_EVAL=true ./mvnw -pl services/taxiagent-agent-service
 * -Dtest=ClassifierServiceEvalIT test}。每次线上错分案例应补充进
 * classifier-regression-samples.json，改分类提示词后跑本评测验证
 * 没有改坏其他标签。</p>
 */
class ClassifierServiceEvalIT {

    private static final double MIN_ACCURACY = 0.9;

    private static ClassifierService service;
    private static List<Sample> samples;

    @BeforeAll
    static void setUp() throws Exception {
        String apiKey = resolveApiKey();
        ClassifierProperties properties = new ClassifierProperties();
        DashScopeChatGateway gateway = new DashScopeChatGateway(
                RestClient.builder().build(), apiKey, properties);
        service = new ClassifierService(gateway, properties);
        samples = new ObjectMapper().readValue(
                getClasspathResource("/classifier-regression-samples.json"),
                new ObjectMapper().getTypeFactory().constructCollectionType(List.class, Sample.class));
    }

    @Test
    void regressionSamplesShouldMeetAccuracyGate() {
        List<String> mismatches = new ArrayList<>();
        int correct = 0;
        for (Sample sample : samples) {
            String actual = service.classify(null, null, sample.utterance());
            if (actual.equals(sample.expected())) {
                correct++;
            } else {
                mismatches.add("\"%s\" 期望 %s 实际 %s".formatted(
                        sample.utterance(), sample.expected(), actual));
            }
        }
        double accuracy = (double) correct / samples.size();
        System.out.printf(Locale.ROOT, "分类评测: %d/%d correct (%.1f%%)%n",
                correct, samples.size(), accuracy * 100);
        mismatches.forEach(m -> System.out.println("MISMATCH: " + m));
        assertThat(accuracy).as("准确率需达 %.0f%%；错例见上方 MISMATCH 输出", MIN_ACCURACY * 100)
                .isGreaterThanOrEqualTo(MIN_ACCURACY);
    }

    private static String resolveApiKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key == null || key.isBlank()) {
            String springKey = System.getenv("SPRING_AI_DASHSCOPE_API_KEY");
            if (springKey != null && !springKey.isBlank()) {
                return springKey;
            }
            throw new IllegalStateException("CLASSIFIER_EVAL=true 需要环境提供 DASHSCOPE_API_KEY");
        }
        return key;
    }

    private static java.io.InputStream getClasspathResource(String path) {
        return ClassifierServiceEvalIT.class.getResourceAsStream(path);
    }

    record Sample(String utterance, String expected) {
    }
}
