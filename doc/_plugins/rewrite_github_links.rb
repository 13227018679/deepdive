# A plugin to rewrite GitHub-friendly links with relative paths to absolute URLs
GITHUB_REPO_BASE_URL = "https://github.com/HazyResearch/deepdive/blob/master"
def rewrite_markdown_links(page)
    # manipulate Markdown links to map ../examples/* to GitHub repo
    page.content = page.content.gsub(
        /(\[[^\]]*\]\()(\.\.\/)+(examples\/[^:\)]*)(#[^\)]*)?\)/,
            "\\1#{GITHUB_REPO_BASE_URL}/\\3\\4)")
end
def rewrite_html_links(page)
    # manipulate HTML links as well to map ../examples/* to GitHub repo
    # because Markdown from {% include ... %} don't go through :pre_render
    page.output = page.output.gsub(
        /href="(\.\.\/)+(examples\/[^":]*)(#[^"]*)?"/,
            "href=\"#{GITHUB_REPO_BASE_URL}/\\2\\3\"")
end

begin
    # jekyll >= 3.x
    Jekyll::Hooks.register :pages, :pre_render, priority: :lowest do |page|
        rewrite_markdown_links(page)
    end
    Jekyll::Hooks.register :pages, :post_render do |page|
        rewrite_html_links(page)
    end
rescue
    # jekyll <= 2.x
    # See: http://stackoverflow.com/a/29234076
    module ChangeLocalMdLinksToHtml
        class Generator < Jekyll::Generator
            def generate(site)
                site.pages.each rewrite_markdown_links
            end
        end
    end
end
