(function () {
    'use strict';

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    function pageItems(page, radius) {
        if (page.totalPages <= 1) return [];
        var values = [0];
        var start = Math.max(1, page.number - radius);
        var end = Math.min(page.totalPages - 2, page.number + radius);
        for (var i = start; i <= end; i++) values.push(i);
        if (page.totalPages > 1) values.push(page.totalPages - 1);
        return values.filter(function (value, index, all) { return index === 0 || value !== all[index - 1]; });
    }

    function pagination(page, onPage, options) {
        options = options || {};
        var root = element('div', 'd-flex flex-wrap justify-content-between align-items-center gap-3 mt-3');
        if (page.totalElements > 0 && (!options.rangeOnlyWhenPaginated || page.totalPages > 1)) {
            var from = page.number * page.size + 1;
            var to = from + page.content.length - 1;
            root.appendChild(element('div', 'text-muted',
                (options.rangePrefix || 'Showing ') + from + '-' + to
                + (options.rangeSeparator || ' of ') + page.totalElements + (options.rangeSuffix || '')));
        }
        if (page.totalPages <= 1) return root;

        var nav = element('nav');
        var ul = element('ul', 'pagination pagination-sm mb-0');
        function add(label, target, disabled, active, ariaLabel) {
            var li = element('li', 'page-item' + (disabled ? ' disabled' : '') + (active ? ' active' : ''));
            var link = element('a', 'page-link', label);
            link.href = '#';
            if (ariaLabel) link.setAttribute('aria-label', ariaLabel);
            link.addEventListener('click', function (event) {
                event.preventDefault();
                if (!disabled && !active) onPage(target);
            });
            li.appendChild(link);
            ul.appendChild(li);
        }
        add('«', page.number - 1, page.first, false, 'Previous');
        var items = pageItems(page, options.radius == null ? 1 : options.radius);
        items.forEach(function (value, index) {
            if (index > 0 && value - items[index - 1] > 1) {
                var gap = element('li', 'page-item disabled');
                gap.appendChild(element('span', 'page-link', '…'));
                ul.appendChild(gap);
            }
            add(String(value + 1), value, false, value === page.number);
        });
        add('»', page.number + 1, page.last, false, 'Next');
        nav.appendChild(ul);
        root.appendChild(nav);

        if (options.jump) {
            var jump = element('div', 'd-flex align-items-center gap-1');
            var input = element('input', 'form-control form-control-sm');
            input.type = 'number';
            input.min = '1';
            input.max = String(page.totalPages);
            input.value = String(page.number + 1);
            input.style.width = '60px';
            var button = element('button', 'btn btn-sm btn-outline-secondary', options.jumpLabel || 'Go');
            button.type = 'button';
            function go() {
                var selected = parseInt(input.value, 10);
                if (selected >= 1 && selected <= page.totalPages) onPage(selected - 1);
            }
            button.addEventListener('click', go);
            input.addEventListener('keydown', function (event) { if (event.key === 'Enter') go(); });
            jump.appendChild(input);
            jump.appendChild(button);
            root.appendChild(jump);
        }
        return root;
    }

    function dateTime(value) {
        return value ? String(value).replace('T', ' ').slice(0, 16) : '';
    }

    function request(url, params) {
        var query = params.toString();
        return fetch(url + (query ? '?' + query : ''), { headers: { Accept: 'application/json' } })
            .then(function (response) {
                if (!response.ok) throw new Error('Request failed: HTTP ' + response.status);
                return response.json();
            });
    }

    window.AjaxPagination = { element: element, pagination: pagination, dateTime: dateTime, request: request };
}());
