use fluxdown_ui_components::SidebarPanel;
use gpui::{Context, IntoElement, ParentElement as _, Styled as _, Window, div, px};

use crate::pages::downloads::DownloadView;

impl DownloadView {
    pub(crate) fn render_sidebar_layout(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> impl IntoElement + use<> {
        let has_sections = self.has_visible_sidebar_section();
        let prefs = self.table_state.read(cx).delegate().prefs();
        let (width, collapsed) = (px(prefs.sidebar_width), prefs.sidebar_collapsed);
        self.sidebar.update(cx, |sidebar, cx| {
            sidebar.set_available(has_sections, cx);
            sidebar.set_layout(width, collapsed, cx);
        });
        let content = self.render_main(cx);
        let mut panel = SidebarPanel::new("downloads-content", &self.sidebar, content, window, cx);
        if panel.is_sidebar_visible() {
            panel = panel.sidebar(self.render_sidebar(window, cx));
        }
        div().flex_1().min_h_0().min_w_0().child(panel)
    }
}
