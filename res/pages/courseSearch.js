'use strict';

/**@typedef {string} departmentName */
/**
 * DeptCode - Course serial number
 * @example F7-010
 * @typedef {string} serialNumber
 */
/**
 * Course attribute code
 * @example CSIE1001
 * @typedef {string} attributeCode
 */
/**
 * Course system number - Class code
 * @typedef {string} systemNumber
 */
/**@typedef {string} courseName */
/**@typedef {string} courseNote */
/**@typedef {string} courseLimit */
/**@typedef {string} courseType */
/**@typedef {int} courseGrade */
/**@typedef {string} classInfo */
/**@typedef {string} classGroup */
/**@typedef {string[]} teachers */
/**@typedef {string[]} tags */
/**@typedef {float} credits */
/**@typedef {boolean} required */
/**@typedef {int} selected */
/**@typedef {int|string} available */
/**@typedef {string[]} time */
/**@typedef {string} moodle */

/**
 * @typedef {{
 *     dn: departmentName,
 *     sn: serialNumber,
 *     ca: attributeCode,
 *     cs: systemNumber,
 *     cn: courseName,
 *     ci: courseNote,
 *     cl: courseLimit,
 *     ct: courseType,
 *     g: courseGrade,
 *     co: classInfo,
 *     cg: classGroup,
 *     ts: teachers,
 *     tg: tags,
 *     c: credits,
 *     r: required,
 *     s: selected,
 *     a: available,
 *     t: time,
 *     m: moodle
 * }} CourseData
 */

/**
 * @typedef {{
 *     got: float,
 *     sweet: float,
 *     cold: float,
 *     rate_count: int,
 *     comment: [{
 *         id: int,
 *         comment: string,
 *         semester: string
 *     }],
 *
 *     parsedRates: {
 *         post_id: {
 *             id: int,
 *             user_id: int,
 *             post_id: int,
 *             got: float,
 *             sweet: float,
 *             cold: float,
 *             like: int,
 *             dislike: int,
 *             hard: int,
 *             recommand: int,
 *             give: int,
 *             course_name: string,
 *             teacher: string
 *         }
 *     },
 *     noData: boolean
 * }} NckuHub
 */

/*ExcludeStart*/
const {
    div,
    input,
    button,
    span,
    svg,
    Signal,
    State,
    ClassList,
    br,
    table,
    tr,
    th, td
} = require('../domHelper');
/*ExcludeEnd*/
/**@type {{add:function(), remove: function(), rules: CSSStyleRule}}*/
const styles = require('./courseSearch.css');

module.exports = function () {
    console.log('courseSearch Init');
    const expendArrow = svg('./res/assets/expand_down_arrow_icon.svg', 'expendDownArrow');
    const searchResult = new Signal();
    const instructorWindow = InstructorWindow();
    const popupWindow = PopupWindow();
    let expandElements;
    let courseSearch;

    // data
    let nckuHubCourseID;
    let urschoolData;

    // quary string
    let lastQueryString;
    let searching;

    function onRender() {
        styles.add();
        search();
    }

    function onDestroy() {
        styles.remove();
    }

    function onkeyup(e) {
        if (e.key === 'Enter') search();
    }

    async function search() {
        if (searching) return;
        searching = true;
        // get all course ID
        nckuHubCourseID = (await fetchApi('/nckuhub')).data;

        // get urschool data
        urschoolData = (await fetchApi('/urschool')).data;

        // generate query string
        const queryData = [];
        for (const /**@type HTMLElement*/ node of courseSearch.childNodes) {
            if (!(node instanceof HTMLInputElement)) continue;
            const value = node.value.trim();
            if (value.length > 0)
                queryData.push(node.name + '=' + encodeURIComponent(value));
        }
        const queryString = queryData.join('&');
        if (queryString === lastQueryString) {
            searching = false;
            return;
        }
        lastQueryString = queryString;

        console.log('Search');
        // fetch data
        fetchApi('/search?' + queryString).then(parseResult);
    }

    /**
     * @param result {{data:CourseData[]}}
     */
    function parseResult(result) {
        console.log(result);
        if (!result.data) {
            searching = false;
            return;
        }

        const font = '18px ' + getComputedStyle(document.body).fontFamily;
        const canvas = new Canvas(font);
        const nckuHubRequestIDs = [];
        const nckuHubResponseData = {};

        // parse result
        let deptLen = 0;
        let timeLen = 0;
        for (const data of result.data) {
            data.dn = data.dn.split(' ')[0];
            let cache;
            if ((cache = canvas.measureText(data.dn).width + 1) > deptLen)
                deptLen = cache;

            // parse
            data.ts = data.ts.replace(/\*/g, '').split(' ').map(i => {
                for (const j of urschoolData) if (j[2] === i) return j;
                return i;
            });
            data.parseedTime = data.t.map(i => {
                i = i.split(',');
                return '[' + i[0] + ']' + i[1]
            }).join(', ');
            if ((cache = canvas.measureText(data.parseedTime).width + 1) > timeLen)
                timeLen = cache;
            delete data.t;

            // nckuhub
            const deptAndID = data.sn.split('-');
            let nckuHubID = nckuHubCourseID[deptAndID[0]];
            if (nckuHubID) nckuHubID = nckuHubID[deptAndID[1]];
            if (data.sn.length > 0 && nckuHubID) {
                nckuHubRequestIDs.push(nckuHubID);
                nckuHubResponseData[data.sn] = new Signal();
            }
        }

        // get nckuhub data
        const chunkSize = 5;
        const nckuHubResponseDataArr = Object.values(nckuHubResponseData);
        for (let i = 0; i < nckuHubRequestIDs.length; i += chunkSize) {
            const chunk = nckuHubRequestIDs.slice(i, i + chunkSize);
            fetchApi('/nckuhub?id=' + chunk.join(',')).then(({data}) => {
                for (let j = 0; j < chunk.length; j++) {
                    /**@type NckuHub*/
                    const nckuhub = data[j];
                    nckuhub.got = parseFloat(nckuhub.got);
                    nckuhub.sweet = parseFloat(nckuhub.sweet);
                    nckuhub.cold = parseFloat(nckuhub.cold);

                    nckuhub.noData = nckuhub.rate_count === 0 && nckuhub.comment.length === 0;

                    nckuhub.parsedRates = nckuhub.rates.reduce((a, v) => {
                        a[v.post_id] = v;
                        return a;
                    }, {});
                    delete data[j].rates;
                    nckuHubResponseDataArr[i + j].set(nckuhub);
                }
            });
        }

        expandElements = [];
        searchResult.set({data: result.data, nckuHubResponseData, deptLen, timeLen});
        expandElements.forEach(i => i());
        expandElements = null;
        searching = false;
    }

    function renderResult(state) {
        if (state) {
            return div('result',
                div('header',
                    span('Dept', 'departmentName', {style: `width:${state.deptLen}px`}),
                    span('Serial', 'serialNumber'),
                    span('Time', 'courseTime', {style: `width:${state.timeLen}px`}),
                    span('Name', 'courseName'),
                    div('nckuhub',
                        span('Reward', 'reward'),
                        span('Sweet', 'sweet'),
                        span('Cool', 'cool'),
                    ),
                ),
                div('body',
                    ...state.data.map((data) => {
                        const infoClass = new ClassList('info');
                        const nckuHubData = state.nckuHubResponseData[data.sn];
                        let courseDetails;
                        const expendButton = expendArrow.cloneNode(true);
                        expendButton.onclick = toggleCourseDetails;
                        expandElements.push(toggleCourseDetails);

                        function toggleCourseDetails() {
                            const show = infoClass.toggle('extend');
                            if (show)
                                courseDetails.style.height = courseDetails.firstChild.clientHeight + "px";
                            else
                                courseDetails.style.height = null;
                        }

                        function openNckuHubDetails() {
                            if (!nckuHubData || !nckuHubData.state || nckuHubData.state.noData) return;
                            popupWindow.set([nckuHubData.state, data]);
                        }

                        return div(infoClass, {
                                onclick: openNckuHubDetails,
                                onmousedown: (e) => {if (e.detail > 1) e.preventDefault();},
                            },
                            div(null,
                                expendButton,
                                span(data.dn, 'departmentName', {style: `width:${state.deptLen}px`}),
                                span(data.sn, 'serialNumber'),
                                span(data.parseedTime, 'courseTime', {style: `width:${state.timeLen}px`}),
                                span(data.cn, 'courseName'),
                            ),

                            // ncku Hub
                            State(nckuHubData, /**@param {NckuHub} nckuhub*/(nckuhub) => {
                                if (nckuhub) {
                                    if (nckuhub.noData) return div();

                                    const reward = nckuhub.got;
                                    const sweet = nckuhub.sweet;
                                    const cool = nckuhub.cold;
                                    return div('nckuhub',
                                        span(reward.toFixed(1), 'reward'),
                                        span(sweet.toFixed(1), 'sweet'),
                                        span(cool.toFixed(1), 'cool'),
                                    );
                                }
                                return span('Loading...', 'nckuhub');
                            }),

                            // details
                            courseDetails = div('expandable', div('details',
                                data.ci.length > 0 ? span(data.ci, 'info') : null,
                                data.cl.length > 0 ? span(data.cl, 'limit red') : null,
                                span('Instructor: '),
                                ...data.ts.map((i) =>
                                    button('instructorBtn', i instanceof Array ? i[2] : i, () => {}, {
                                        onmouseenter: (e) => instructorWindow.set({
                                            target: e.target,
                                            offsetY: courseSearch.scrollTop,
                                            data: i
                                        }),
                                        onmouseleave: () => instructorWindow.set(null)
                                    })
                                ),
                            )),
                        );
                    }),
                ),
                instructorWindow,
                popupWindow,
            );
        }
        return div();
    }

    return courseSearch = div('courseSearch',
        {onRender, onDestroy},
        input(null, 'Serial number', 'serialNumber', {onkeyup, name: 'serial'}),
        input(null, 'Course name', 'courseName', {onkeyup, name: 'course'}),
        input(null, 'Dept ID', 'deptId', {onkeyup, name: 'dept', value: 'F7'}),
        input(null, 'Instructor', 'instructor', {onkeyup, name: 'teacher'}),
        input(null, 'Day', 'day', {onkeyup, name: 'day'}),
        input(null, 'Grade', 'grade', {onkeyup, name: 'grade'}),
        input(null, 'Section', 'section', {onkeyup, name: 'section'}),
        button(null, 'search', search),
        State(searchResult, renderResult),
    );
};

function InstructorWindow() {
    const signal = new Signal();
    const state = State(signal, (state) => {
        if (state && state.data instanceof Array) {
            const bound = state.target.getBoundingClientRect();
            const [id, mod,
                name, dept, job,
                recommend, reward, articulate, pressure, sweet,
                averageScore, academicQualifications, note, nickname, rollCall
            ] = state.data;
            return div('instructorWindow', {
                    'style': `top:${bound.top + state.offsetY - 340}px; left:${bound.left}px`
                },
                span('Name: ' + name),
                recommend !== -1 && reward !== -1 && articulate !== -1 && pressure !== -1 && sweet !== -1
                    ? table(null,
                        tr(null, th('Recommend'), th('Reward'), th('Articulate'), th('Pressure'), th('Sweet')),
                        tr(null, td(recommend, getColor(recommend)), td(reward, getColor(reward)), td(articulate, getColor(articulate)), td(pressure, getColor(pressure)), td(sweet, getColor(sweet))),
                    )
                    : null,
                span('Average score: ' + averageScore),
                span('Note: ' + note),
                span('Nickname: ' + nickname),
                span('Department: ' + dept),
                span('Job title: ' + job),
                span('Roll call method: ' + rollCall),
                span('Academic qualifications: ' + academicQualifications),
            );
        }
        return div();
    });
    state.set = signal.set;
    return state;
}

function getColor(number) {
    return number < 2 ? 'red' : number < 4 ? 'yellow' : 'blue';
}

function PopupWindow() {
    const popupSignal = new Signal();
    const popupClass = new ClassList('popupWindow');
    const popupState = State(popupSignal, /**@param {[NckuHub, CourseData]} data*/(data) => {
        if (!data) return div();
        const [nckuhub, ncku] = data;
        popupClass.add('open');
        return div(null,
            button(null, 'x', () => popupClass.remove('open')),
            // rates
            span(`Evaluation(${nckuhub.rate_count})`, 'title'),
            div('rates',
                div(null, div('rateBox',
                    span('Reward'),
                    span(nckuhub.got.toFixed(1)),
                )),
                div(null, div('rateBox',
                    span('Sweetness'),
                    span(nckuhub.sweet.toFixed(1)),
                )),
                div(null, div('rateBox',
                    span('Cool'),
                    span(nckuhub.cold.toFixed(1)),
                )),
            ),
            // comment
            span(`Comments(${nckuhub.comment.length})`, 'title'),
            div('comments',
                ...nckuhub.comment.map(comment => div('commentBlock',
                    span(comment.semester, 'semester'),
                    span(comment.comment, 'comment'),
                )),
            ),
            br(),
            br(),
            br(),
            br(),
            span(JSON.stringify(nckuhub, null, 2)),
        );
    });
    const popupWindow = div(popupClass, popupState);
    popupWindow.set = popupSignal.set;
    return popupWindow;
}

function Canvas(font) {
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d");
    context.font = font;
    return context;
}
