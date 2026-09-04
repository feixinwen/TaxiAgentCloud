package com.fancy.taxiagent.user.poi;

import com.fancy.taxiagent.user.mapper.UserPoiMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 容器验证 POI 归属校验、逻辑删除与 order 分组的集成测试。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "taxiagent.rocketmq.enabled=false",
                "taxiagent.rocketmq.publish-interval-ms=3600000"
        }
)
class PoiServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_user");

    @Autowired
    private PoiService poiService;

    @Autowired
    private UserPoiMapper userPoiMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PoiView poi(String tag, String name, String address, String longitude, String latitude) {
        return new PoiView(
                null,
                tag,
                name,
                address,
                longitude == null ? null : new BigDecimal(longitude),
                latitude == null ? null : new BigDecimal(latitude),
                null);
    }

    @Test
    void shouldCreateAndGetOwnPoi() {
        PoiView created = poiService.create(60001L, poi("家", "我的家", "上海市黄浦区", "121.4737", "31.2304"));

        assertThat(created.id()).isNotNull();
        assertThat(created.poiTag()).isEqualTo("家");
        assertThat(created.poiName()).isEqualTo("我的家");
        assertThat(created.createdAt()).isNotNull();

        PoiView fetched = poiService.get(60001L, created.id());
        assertThat(fetched).isEqualTo(created);
    }

    @Test
    void shouldListOnlyOwnPoisOrderedByIdDesc() {
        PoiView first = poiService.create(60002L, poi("家", "家A", null, "121.1", "31.1"));
        PoiView second = poiService.create(60002L, poi("公司", "公司B", "地址", "121.2", "31.2"));
        poiService.create(60003L, poi("家", "别人的家", null, "121.3", "31.3"));

        List<PoiView> list = poiService.list(60002L);

        assertThat(list).extracting(PoiView::id)
                .containsExactly(second.id(), first.id());
    }

    @Test
    void shouldReturn404WhenPoiMissing() {
        assertThatThrownBy(() -> poiService.get(60004L, 999999L))
                .isInstanceOf(PoiNotFoundException.class);
    }

    @Test
    void shouldReturn404WhenGettingOthersPoi() {
        PoiView created = poiService.create(60005L, poi("家", "属于60005", null, "121.1", "31.1"));

        assertThatThrownBy(() -> poiService.get(60006L, created.id()))
                .isInstanceOf(PoiNotFoundException.class);
    }

    @Test
    void shouldReturn404WhenUpdatingOthersPoi() {
        PoiView created = poiService.create(60007L, poi("家", "属于60007", null, "121.1", "31.1"));

        assertThatThrownBy(() -> poiService.update(
                60008L, created.id(), poi("公司", "篡改", null, "121.2", "31.2")))
                .isInstanceOf(PoiNotFoundException.class);

        // 原始数据未被篡改
        assertThat(poiService.get(60007L, created.id()).poiName()).isEqualTo("属于60007");
    }

    @Test
    void shouldReturn404WhenDeletingOthersPoi() {
        PoiView created = poiService.create(60009L, poi("家", "属于60009", null, "121.1", "31.1"));

        assertThatThrownBy(() -> poiService.delete(60010L, created.id()))
                .isInstanceOf(PoiNotFoundException.class);
        assertThat(poiService.get(60009L, created.id())).isNotNull();
    }

    @Test
    void shouldReturn404WhenDeletingMissingPoi() {
        assertThatThrownBy(() -> poiService.delete(60011L, 999999L))
                .isInstanceOf(PoiNotFoundException.class);
    }

    @Test
    void shouldUpdateOwnPoi() {
        PoiView created = poiService.create(60012L, poi("家", "旧名字", "旧地址", "121.1", "31.1"));

        poiService.update(60012L, created.id(), poi("公司", "新名字", "新地址", "121.2", "31.2"));

        PoiView updated = poiService.get(60012L, created.id());
        assertThat(updated.poiTag()).isEqualTo("公司");
        assertThat(updated.poiName()).isEqualTo("新名字");
        assertThat(updated.poiAddress()).isEqualTo("新地址");
        assertThat(updated.longitude()).isEqualByComparingTo(new BigDecimal("121.2"));
        assertThat(updated.latitude()).isEqualByComparingTo(new BigDecimal("31.2"));
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
    }

    @Test
    void shouldHidePoiAfterLogicalDelete() {
        PoiView created = poiService.create(60013L, poi("家", "将被删除", null, "121.1", "31.1"));

        poiService.delete(60013L, created.id());

        assertThatThrownBy(() -> poiService.get(60013L, created.id()))
                .isInstanceOf(PoiNotFoundException.class);
        assertThat(poiService.list(60013L)).isEmpty();
        // 底层行仍存在，仅 is_deleted 置 1
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM user_poi WHERE id = ?", Integer.class, created.id());
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void shouldRejectBlankTag() {
        assertThatThrownBy(() -> poiService.create(60014L, poi("   ", "名字", null, "121.1", "31.1")))
                .isInstanceOf(InvalidPoiException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> poiService.create(60015L, poi("家", "   ", null, "121.1", "31.1")))
                .isInstanceOf(InvalidPoiException.class);
    }

    @Test
    void shouldRejectInvalidCoordinates() {
        assertThatThrownBy(() -> poiService.create(60016L, poi("家", "名字", null, "181", "31.1")))
                .isInstanceOf(InvalidPoiException.class);
        assertThatThrownBy(() -> poiService.create(60016L, poi("家", "名字", null, "121.1", "91")))
                .isInstanceOf(InvalidPoiException.class);
        assertThatThrownBy(() -> poiService.create(60016L, poi("家", "名字", null, null, "31.1")))
                .isInstanceOf(InvalidPoiException.class);
    }

    @Test
    void shouldRejectInvalidCoordinatesOnUpdate() {
        PoiView created = poiService.create(60017L, poi("家", "名字", null, "121.1", "31.1"));

        assertThatThrownBy(() -> poiService.update(
                60017L, created.id(), poi("家", "名字", null, "180.1", "31.1")))
                .isInstanceOf(InvalidPoiException.class);
    }

    @Test
    void shouldGroupPoisByTagForOrder() {
        poiService.create(60018L, poi("其他", "机场", null, "121.1", "31.1"));
        PoiView homeOldest = poiService.create(60018L, poi("家", "家1", null, "121.2", "31.2"));
        PoiView work = poiService.create(60018L, poi("公司", "公司1", null, "121.3", "31.3"));
        poiService.create(60018L, poi("其他", "火车站", null, "121.4", "31.4"));
        poiService.create(60018L, poi("家", "家2", null, "121.5", "31.5"));

        PoiOrderView orderView = poiService.orderView(60018L);

        // 与单体一致：按 id 倒序迭代，同标签后者覆盖，最终保留最早创建的家/公司；其余标签进 other
        assertThat(orderView.home().id()).isEqualTo(homeOldest.id());
        assertThat(orderView.work().id()).isEqualTo(work.id());
        assertThat(orderView.other()).extracting(PoiView::poiName)
                .containsExactly("火车站", "机场");
    }

    @Test
    void shouldReturnEmptyOrderViewWithoutPois() {
        PoiOrderView orderView = poiService.orderView(60019L);

        assertThat(orderView.home()).isNull();
        assertThat(orderView.work()).isNull();
        assertThat(orderView.other()).isEmpty();
    }
}
